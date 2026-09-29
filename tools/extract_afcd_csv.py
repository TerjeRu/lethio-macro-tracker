#!/usr/bin/env python3
"""Convert AFCD food details and nutrient workbooks to CSV."""

from __future__ import annotations

import argparse
import csv
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

from extract_cofid_csv import load_shared_strings, read_rows, sheet_files  # noqa: E402

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

DETAILS_SHEETS = ["Contents", "Food details"]
PROFILES_SHEETS = ["Contents", "All solids & liquids per 100 g",
                   "Liquids only per 100 mL"]

DETAILS_HEADER = {
    1: "Public Food Key",
    4: "Food Name",
}
PROFILES_HEADER = {
    1: "Public Food Key",
    4: "Food Name",
    5: "Energy with dietary fibre, equated \n(kJ)",
    6: "Energy, without dietary fibre, equated \n(kJ)",
    8: "Protein \n(g)",
    10: "Fat, total \n(g)",
    12: "Total dietary fibre \n(g)",
    20: "Total sugars (g)",
    39: "Available carbohydrate, without sugar alcohols \n(g)",
    73: "Sodium (Na) \n(mg)",
    192: "Total saturated fatty acids, equated \n(g)",
}

DETAILS_BANNER = "Release 3 - Food details"
PROFILES_BANNER = "Release 3 - Nutrient profiles (per 100 g)"

OUTPUT_HEADERS = ["Public Food Key", "Food Name", "E_KJ", "PROTEIN", "FAT",
                  "AVAIL_CHO", "FIBRE", "SUGAR", "SATFAT", "SODIUM"]

_NUMERIC_RE = re.compile(r"-?\d+(\.\d+)?([Ee][+-]?\d+)?$")

def fail(message: str) -> None:
    print(f"  ! {message}", file=sys.stderr)
    sys.exit(1)

def load_sheet(xlsx_path: str, sheet_name: str, sheet_list: list[str],
               banner: str, header: dict[int, str]) -> list[dict[int, str]]:

    with zipfile.ZipFile(xlsx_path) as zf:
        workbook = ET.fromstring(zf.read("xl/workbook.xml"))
        sheets = [s.attrib.get("name") for s in workbook.iter(MAIN + "sheet")]
        if sheets != sheet_list:
            fail(f"{xlsx_path}: expected sheets {sheet_list}, workbook holds {sheets}")
        files = sheet_files(zf)
        if sheet_name not in files:
            fail(f"{xlsx_path}: sheet {sheet_name!r} has no relationship target")
        strings = load_shared_strings(zf)
        rows = [cells for _, cells in read_rows(zf, files[sheet_name], strings)]

    if not rows:
        fail(f"{xlsx_path}: sheet {sheet_name!r} is empty")
    if rows[0].get(1) != banner:
        fail(f"{xlsx_path}: {sheet_name!r} row 1 is {rows[0].get(1)!r}, "
             f"expected {banner!r}")
    for index, text in header.items():
        if rows[2].get(index) != text:
            fail(f"{xlsx_path}: {sheet_name!r} header column {index} is "
                 f"{rows[2].get(index)!r}, expected {text!r}")
    return [r for r in rows[3:] if any(r.values())]

def extract(details_path: str, profiles_path: str, csv_path: str) -> tuple[int, int]:
    details = load_sheet(details_path, "Food details", DETAILS_SHEETS,
                         DETAILS_BANNER, DETAILS_HEADER)
    profiles = load_sheet(profiles_path, "All solids & liquids per 100 g",
                          PROFILES_SHEETS, PROFILES_BANNER, PROFILES_HEADER)
    liquids = load_sheet(profiles_path, "Liquids only per 100 mL",
                         PROFILES_SHEETS,
                         "Release 3 - Nutrient profiles (per 100 mL)",
                         {1: "Public Food Key", 4: "Food Name"})

    names = {r[1]: r[4] for r in details}
    pkeys = [r[1] for r in profiles]
    lkeys = [r[1] for r in liquids]
    if set(pkeys) != set(names):
        fail(f"profile keys ({len(set(pkeys))}) do not match details keys "
             f"({len(set(names))})")
    if not set(lkeys) <= set(pkeys):
        fail(f"{len(set(lkeys) - set(pkeys))} liquid-sheet keys are not in the "
             f"per-100g sheet")
    for r in profiles:
        if names.get(r[1]) != r.get(4):
            fail(f"food name for key {r[1]} differs between the workbooks")

    for r in profiles:
        for col in (5, 8, 10, 12, 20, 39, 73, 192):
            text = (r.get(col) or "").strip()
            if text and not _NUMERIC_RE.fullmatch(text):
                fail(f"non-numeric value {text!r} in column {col} "
                     f"of key {r[1]}")

    with open(csv_path, "w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(OUTPUT_HEADERS)
        for r in profiles:
            writer.writerow([
                r[1], r[4], r.get(5, ""), r.get(8, ""), r.get(10, ""),
                r.get(39, ""), r.get(12, ""), r.get(20, ""), r.get(192, ""),
                r.get(73, ""),
            ])
    return len(profiles), len(set(lkeys))

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("details", help="path to 'AFCD Release 3 - Food Details.xlsx'")
    parser.add_argument("profiles", help="path to 'AFCD Release 3 - Nutrient profiles.xlsx'")
    parser.add_argument("csv_out", help="path to write the extracted CSV to")
    args = parser.parse_args()
    rows, liquids = extract(args.details, args.profiles, args.csv_out)
    print(f"Wrote {rows:,} rows x {len(OUTPUT_HEADERS)} columns to {args.csv_out} "
          f"({liquids:,} liquid keys also present on the per-100mL sheet)")

if __name__ == "__main__":
    main()
