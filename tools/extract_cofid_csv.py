#!/usr/bin/env python3
"""Extract the UK CoFID 2021 nutrient workbook to a CSV the pipeline can read.

Reads one known file -- `CoFID_2021.xlsx` (Public Health England, Composition of Foods
Integrated Dataset, 2021, Open Government Licence v3.0) -- and writes a UTF-8 CSV. Like
`extract_bls_csv.py` it is not a general-purpose xlsx reader: it verifies
the sheet list, the header text and the column layout it knows, and fails loudly if a
future reissue changes any of them.

The pipeline maps eight schema fields, drawn from two of the workbook's fifteen sheets:

- `1.3 Proximates`: kcal, protein, fat, carbs, AOAC fibre, total sugars, and saturated
  fatty acids *per 100g food* (`SATFOD`, not its per-100g-fatty-acid sibling `SATFAC`).
- `1.4 Inorganics`: sodium (mg/100g).

`1.5 Vitamins` supplies no schema field -- nothing on `foods` maps to retinol or thiamin --
so it is not emitted, the same reasoning by which the BLS extractor drops provenance
columns. Its sheet name stays pinned by the sheet-list check, so a reissue that removes
or renames it still fails loudly.

Each nutrient sheet has a three-row header: row 1 is the human name with the unit
(`Sodium (mg)`), row 2 the nutrient code (`NA`), row 3 a longer description. The CSV is
written with the row-2 codes as headers, so the loader looks columns up by code exactly
as `load_bls` does; the extractor has already verified what each code means. Numeric
cell text passes through untouched -- the file carries spreadsheet noise like
`1.1000000000000001`, and rounding here would hide it from the loader and its quality
gates.

Sparse cells are the trap, same as in BLS: empty cells are skipped entirely in the XML,
so values are placed by each cell's `r="C7"` column reference, never by document order.

The sheets are joined on the food code in column A. All three sheets carry the same
codes in the same order in this snapshot, but the join is by code *value* -- and the
code sets and the food names are verified to agree across the two sheets read, so a
future reissue whose sheets drift fails here instead of shifting every sodium value by
one food. One code, `13-669`, occurs twice on two different foods; both rows are
emitted and the loader rejects both (see the findings, §6) -- the extractor must not
silently collapse them.

Usage:
    python tools/extract_cofid_csv.py raw/cofid/CoFID_2021.xlsx \\
        build/cofid.csv
"""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile
import xml.etree.ElementTree as ET

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
RELS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"

# The one workbook this tool understands, as published 2026-08-23. Checked against the
# sheet list and the header rows, so a reissue that renames a sheet or moves a column
# fails here instead of producing a plausible-looking CSV with values under the wrong
# names.
SHEET_NAMES = (
    "List of tables", "1.1 Notes", "1.2 Factors", "1.3 Proximates",
    "1.4 Inorganics", "1.5 Vitamins", "1.6 Vitamin Fractions",
    "1.7 (SFA per 100gFA)", "1.8 (SFA per 100gFood)",
    "1.9 (MUFA per 100FA)", "1.10 (MUFA per 100gFood)",
    "1.11 (PUFA per 100gFA)", "1.12 (PUFA per 100gFood)",
    "1.13 Phytosterols", "1.14 Organic Acids",
)

# column index -> (row-1 header text, row-2 nutrient code). Only the columns the
# pipeline could use are pinned; every other column is unverified and
# dropped. `1.4`'s A1 cell holds a single space, not 'Food Code' -- pinned exactly
# so a reissue that fixes the typo fails loudly rather than shifting a column.
PROXIMATES_HEADER = {
    1: ("Food Code", None),
    2: ("Food Name", None),
    10: ("Protein (g)", "PROT"),
    11: ("Fat (g)", "FAT"),
    12: ("Carbohydrate (g)", "CHO"),
    13: ("Energy (kcal) (kcal)", "KCALS"),
    14: ("Energy (kJ) (kJ)", "KJ"),
    17: ("Total sugars (g)", "TOTSUG"),
    26: ("AOAC fibre (g)", "AOACFIB"),
    28: ("Satd FA /100g fd (g)", "SATFOD"),
}
INORGANICS_HEADER = {
    1: (" ", None),
    2: ("Food Name", None),
    8: ("Sodium (mg)", "NA"),
}

OUTPUT_HEADERS = ["Food Code", "Food Name", "PROT", "FAT", "CHO", "KCALS",
                  "TOTSUG", "AOACFIB", "SATFOD", "NA"]


def column_index(ref: str) -> int:
    """A1 -> 1, C7 -> 3, AB4 -> 28."""
    index = 0
    for ch in ref:
        if ch.isalpha():
            index = index * 26 + (ord(ch) - 64)
        else:
            break
    return index


def load_shared_strings(zip_file: zipfile.ZipFile) -> list[str]:
    root = ET.fromstring(zip_file.read("xl/sharedStrings.xml"))
    return ["".join(t.text or "" for t in si.iter(MAIN + "t")) for si in root]


def cell_text(cell: ET.Element, strings: list[str]) -> str:
    """The cell's literal content: a shared string or numeric text, verbatim."""
    kind = cell.attrib.get("t", "n")
    value = cell.find(MAIN + "v")
    text = value.text if value is not None else ""
    if kind == "s":
        try:
            return strings[int(text)]
        except (ValueError, IndexError):
            fail(f"shared string index {text!r} is not valid")
    if kind != "n":
        fail(f"cell type t={kind!r} is not handled by this extractor")
    return text


def fail(message: str) -> None:
    print(f"  ! {message}", file=sys.stderr)
    sys.exit(1)


def sheet_files(zip_file: zipfile.ZipFile) -> dict[str, str]:
    """Sheet name -> path inside the zip, resolved through the workbook rels.

    Not sheet1.xml, sheet2.xml ... in listed order: the workbook's sheetId order
    differs from its display order, so a name is mapped to its file through
    `xl/_rels/workbook.xml.rels` rather than assumed.
    """
    rels = ET.fromstring(zip_file.read("xl/_rels/workbook.xml.rels"))
    targets = {r.attrib["Id"]: r.attrib["Target"] for r in rels}
    workbook = ET.fromstring(zip_file.read("xl/workbook.xml"))
    mapping = {}
    for sheet in workbook.iter(MAIN + "sheet"):
        target = targets.get(sheet.attrib.get(RELS + "id", ""), "")
        mapping[sheet.attrib["name"]] = "xl/" + target.lstrip("/")
    return mapping


def read_rows(zip_file: zipfile.ZipFile, sheet_path: str, strings: list[str]):
    """Yields (row_number, {column_index: text}) for every row, header rows included."""
    with zip_file.open(sheet_path) as fh:
        for _, row in ET.iterparse(fh, events=("end",)):
            if row.tag != MAIN + "row":
                continue
            cells = {}
            for c in row:
                if c.tag != MAIN + "c":
                    continue
                # Sparse cells: each carries its own r="C7" reference, so place by
                # column, never by document order.
                cells[column_index(c.attrib["r"])] = cell_text(c, strings)
            yield int(row.attrib.get("r", "0")), cells
            row.clear()


def verify_header(rows, expected: dict[int, tuple[str, str | None]], sheet: str) -> None:
    """Verifies the two header rows at the pinned columns, and fails loudly otherwise."""
    by_number = dict(rows)
    if 1 not in by_number or 2 not in by_number:
        fail(f"{sheet} has no row 1 and 2 header")
    for ci, (name, code) in expected.items():
        for row_number, index in ((1, 0), (2, 1)):
            expected_text = (name, code)[index]
            if expected_text is None:
                continue
            actual = by_number[row_number].get(ci)
            if actual != expected_text:
                fail(f"{sheet} row {row_number} column {ci} is {actual!r}, "
                     f"expected {expected_text!r}")


def extract(xlsx_path: str, csv_path: str) -> tuple[int, int]:
    with zipfile.ZipFile(xlsx_path) as zf:
        files = sheet_files(zf)
        names = sorted(files)
        if names != sorted(SHEET_NAMES):
            fail(f"expected the 15-sheet CoFID workbook, found {names}")
        for name in ("1.3 Proximates", "1.4 Inorganics"):
            if files[name] not in zf.namelist():
                fail(f"no {files[name]} in the workbook")

        strings = load_shared_strings(zf)

        proximates = list(read_rows(zf, files["1.3 Proximates"], strings))
        inorganics = list(read_rows(zf, files["1.4 Inorganics"], strings))
        verify_header(proximates, PROXIMATES_HEADER, "1.3 Proximates")
        verify_header(inorganics, INORGANICS_HEADER, "1.4 Inorganics")

        # Data starts at row 4; rows 1-3 are the header block.
        prox_rows = [(r, c) for r, c in proximates if r > 3]
        inorg_by_code: dict[str, list[tuple[str, str]]] = {}
        for r, cells in inorganics:
            if r > 3:
                inorg_by_code.setdefault(cells.get(1, ""), []).append(
                    (cells.get(2, ""), cells.get(8, ""))
                )

        prox_codes = [c.get(1, "") for _, c in prox_rows]
        if sorted(set(prox_codes)) != sorted(inorg_by_code):
            fail("1.3 Proximates and 1.4 Inorganics do not carry the same food codes")

        written = 0
        with open(csv_path, "w", encoding="utf-8", newline="") as out:
            writer = csv.writer(out)
            writer.writerow(OUTPUT_HEADERS)
            for _, cells in prox_rows:
                code = cells.get(1, "")
                inorg_cells = inorg_by_code.get(code, [])
                if not inorg_cells:
                    fail(f"food code {code!r} missing from 1.4 Inorganics")
                name_14, sodium = inorg_cells.pop(0)
                # The two sheets describe the same foods; a name that differs per code
                # means a reissue misaligned them, and the joined row would be wrong.
                if name_14 != cells.get(2, ""):
                    fail(f"food code {code!r}: name {name_14!r} in 1.4 Inorganics "
                         f"differs from {cells.get(2, '')!r} in 1.3 Proximates")
                writer.writerow([
                    code, cells.get(2, ""),
                    cells.get(10, ""), cells.get(11, ""), cells.get(12, ""),
                    cells.get(13, ""), cells.get(17, ""), cells.get(26, ""),
                    cells.get(28, ""), sodium,
                ])
                written += 1
        # A 1.4 row no 1.3 row consumed means the multiplicities differ per code
        # (the sets checked above agree, so this is the only drift left).
        leftover = [code for code, entries in inorg_by_code.items() if entries]
        if leftover:
            fail(f"1.4 Inorganics has {len(leftover)} unconsumed row(s), first code "
                 f"{leftover[0]!r}")

    return written, len(OUTPUT_HEADERS)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xlsx", help="path to CoFID_2021.xlsx")
    parser.add_argument("csv_out", help="path to write the extracted CSV to")
    args = parser.parse_args()
    rows, columns = extract(args.xlsx, args.csv_out)
    print(f"Wrote {rows:,} data rows x {columns} columns to {args.csv_out}")


if __name__ == "__main__":
    main()
