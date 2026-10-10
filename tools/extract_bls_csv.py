#!/usr/bin/env python3
"""Extract the German BLS 4.0 nutrient spreadsheet to a CSV the pipeline can read.

Reads one known file -- `BLS_4_0_Daten_2025_DE.xlsx` (Max Rubner-Institut,
Bundeslebensmittelschlüssel 4.0, 2025, CC BY 4.0) -- and writes a UTF-8 CSV. It is
not a general-purpose xlsx reader: it verifies the sheet name, the header
text and the column layout it knows, and fails loudly if a future reissue changes any
of them.

Standard library only, like every tool in this pipeline. An .xlsx is a zip of XML:
`zipfile` plus `xml.etree.ElementTree` reads it with no dependency. This file holds its
strings in `xl/sharedStrings.xml` and its grid in `xl/worksheets/sheet1.xml`, with no
inline strings.

Only the columns the pipeline could use are emitted: the identifier (`BLS Code`), both
names (`Lebensmittelbezeichnung`, `Food name`), and the 138 nutrient *value* columns.
Each nutrient's `Datenherkunft` (origin: measured, calculated, adopted...) and
`Referenz` (human-readable citation) columns are dropped -- they are provenance for
human readers, would roughly triple the file, and map to nothing on `foods`. The
trailing `Hinweis` column is dropped for the same reason: it is populated for 4 of
7,140 rows, with free-text notes about sweeteners. To add provenance, re-extract rather
than silently widening this output.

Extraction is faithful: headers are kept verbatim (the unit in `[g/100g]` is the only
place a column's unit is recorded), and numeric cell text is passed through untouched
-- the file carries spreadsheet noise like `9.3000000000000007`, and rounding here
would hide it from the loader and its quality gates.

Sparse cells are the trap. Empty cells are skipped entirely in the XML, so rows are
handled by each cell's `r="C7"` column reference rather than by document order --
appending in order would silently shift every value after the first gap left.

Usage:
    python tools/extract_bls_csv.py raw/bls/BLS_4_0_2025_DE/BLS_4_0_Daten_2025_DE.xlsx \\
        raw/bls/BLS_4_0_Daten_2025_DE.csv
"""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile
import xml.etree.ElementTree as ET

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

# The one file this tool understands. Checked against the workbook and the header
# row, so a reissued BLS with a moved or renamed column fails here instead of
# producing a plausible-looking CSV with the values under the wrong names.
SHEET_NAME = "BLS_4_0_Daten_2025_DE"
NAME_COLUMNS = ("BLS Code", "Lebensmittelbezeichnung", "Food name")
NOTE_COLUMN = "Hinweis"


def column_index(ref: str) -> int:
    """A1 -> 1, C7 -> 3, PB7141 -> 418."""
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


def extract(xlsx_path: str, csv_path: str) -> tuple[int, int]:
    with zipfile.ZipFile(xlsx_path) as zf:
        workbook = ET.fromstring(zf.read("xl/workbook.xml"))
        sheets = [s.attrib.get("name") for s in workbook.iter(MAIN + "sheet")]
        if sheets != [SHEET_NAME]:
            fail(f"expected the single sheet {SHEET_NAME!r}, workbook holds {sheets}")
        if "xl/worksheets/sheet1.xml" not in zf.namelist():
            fail("no xl/worksheets/sheet1.xml in the workbook")
        strings = load_shared_strings(zf)

        kept_columns: list[int] = []
        header: list[str] = []
        data_rows = 0
        header_done = False

        with zf.open("xl/worksheets/sheet1.xml") as sheet, open(
            csv_path, "w", encoding="utf-8", newline=""
        ) as out:
            writer = csv.writer(out)

            for _, row in ET.iterparse(sheet, events=("end",)):
                if row.tag != MAIN + "row":
                    continue

                if not header_done:
                    # Header row. Verify the layout this tool was written against,
                    # then decide which columns to keep.
                    cells = {
                        column_index(c.attrib["r"]): c
                        for c in row
                        if c.tag == MAIN + "c"
                    }
                    header = [cell_text(cells[i], strings) for i in range(1, max(cells) + 1)]
                    if header[: len(NAME_COLUMNS)] != list(NAME_COLUMNS):
                        fail(f"first columns are {header[:3]!r}, expected {NAME_COLUMNS}")
                    if header[-1] != NOTE_COLUMN:
                        fail(f"last column is {header[-1]!r}, expected {NOTE_COLUMN!r}")

                    # After the name columns the sheet is a repeating triple per
                    # nutrient: "<CODE> <German name> [unit]", then
                    # "<CODE> Datenherkunft" and "<CODE> Referenz". Verify each
                    # provenance column carries its value column's code, and that
                    # every value header ends in a bracketed unit.
                    for i in range(len(NAME_COLUMNS) + 1, len(header)):
                        position = (i - len(NAME_COLUMNS) - 1) % 3
                        if position == 0:
                            if not header[i - 1].rstrip().endswith("]"):
                                fail(
                                    f"value column {i} is {header[i - 1]!r}, "
                                    "missing a bracketed unit"
                                )
                            continue
                        code = header[i - position - 1].split()[0]
                        suffix = " Datenherkunft" if position == 1 else " Referenz"
                        if header[i - 1] != code + suffix:
                            fail(
                                f"column {i} is {header[i - 1]!r}, "
                                f"expected {code + suffix!r}"
                            )

                    # Keep the three name columns and every value column -- exactly
                    # the headers carrying a bracketed unit. Provenance and note
                    # columns have none.
                    kept_columns = [
                        i for i in range(1, len(header) + 1)
                        if i <= len(NAME_COLUMNS) or header[i - 1].rstrip().endswith("]")
                    ]
                    writer.writerow([header[i - 1] for i in kept_columns])
                    header_done = True
                    row.clear()
                    continue

                # Data row. Cells are sparse -- each carries its own reference --
                # so place every value by its column, not by document order.
                cells = {
                    column_index(c.attrib["r"]): c
                    for c in row
                    if c.tag == MAIN + "c"
                }
                writer.writerow(
                    cell_text(cells[i], strings) if i in cells else "" for i in kept_columns
                )
                data_rows += 1
                row.clear()

    return data_rows, len(kept_columns)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xlsx", help="path to BLS_4_0_Daten_2025_DE.xlsx")
    parser.add_argument("csv_out", help="path to write the extracted CSV to")
    args = parser.parse_args()
    rows, columns = extract(args.xlsx, args.csv_out)
    print(f"Wrote {rows:,} data rows x {columns} columns to {args.csv_out}")


if __name__ == "__main__":
    main()
