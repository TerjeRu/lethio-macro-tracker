#!/usr/bin/env python3
"""Convert the BLS nutrient workbook to CSV."""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile
import xml.etree.ElementTree as ET

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

SHEET_NAME = "BLS_4_0_Daten_2025_DE"
NAME_COLUMNS = ("BLS Code", "Lebensmittelbezeichnung", "Food name")
NOTE_COLUMN = "Hinweis"

def column_index(ref: str) -> int:

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

                    kept_columns = [
                        i for i in range(1, len(header) + 1)
                        if i <= len(NAME_COLUMNS) or header[i - 1].rstrip().endswith("]")
                    ]
                    writer.writerow([header[i - 1] for i in kept_columns])
                    header_done = True
                    row.clear()
                    continue

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
