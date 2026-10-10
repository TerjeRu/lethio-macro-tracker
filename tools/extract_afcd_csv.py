#!/usr/bin/env python3
"""Extract the Australian AFCD Release 3 workbooks to a CSV the pipeline can read.

Reads two known files -- `AFCD Release 3 - Food Details.xlsx` (FSANZ, Australian Food
Composition Database, Release 3, CC BY-SA 3.0 AU plus an additional term) for the names,
and `AFCD Release 3 - Nutrient profiles.xlsx` for the values -- and writes a UTF-8 CSV.
Like `extract_bls_csv.py` and `extract_cofid_csv.py` it is not a
general-purpose xlsx reader: it verifies the sheet lists, the header text and the column
layout it knows, and fails loudly if a future reissue changes any of them.

Only the columns the pipeline could use are emitted: the identifier (`Public Food Key`),
the name, and the eight nutrient value columns that map to `foods`:

- `Energy with dietary fibre, equated (kJ)` -- the core-nutrients energy (INFOODs
  tagname ENERC); there is NO kcal column anywhere in the workbook, so the loader
  converts kJ to kcal.
- `Protein (g)`, `Fat, total (g)`, `Total dietary fibre (g)`, `Total sugars (g)`,
  `Sodium (Na) (mg)`.
- `Available carbohydrate, without sugar alcohols (g)` -- this database's carbohydrate
  concept; there is no total-carbohydrate column. Measured: energy-with-fibre in kJ
  reproduces 4 kcal/g protein + 9 fat + 4 available carbohydrate + 7 alcohol + 2 fibre
  on all 1,588 rows within the pipeline gate's tolerance.
- `Total saturated fatty acids, equated (g)` -- the per-100g-food column, not its
  per-100g-fatty-acid `(%T)` sibling.

The workbook carries two profile sheets: `All solids & liquids per 100 g` (all 1,588
foods) and `Liquids only per 100 mL` (the same 213 liquid keys on a volume basis). Only
the per-100 g sheet is read -- it is the one record per food, and the project stores
grams-per-100g. The 100 mL sheet's key set is verified to be a subset, so a reissue that
moves foods between bases fails loudly rather than silently changing which rows are read.

Extraction is faithful: numeric cell text passes through untouched (the file carries
spreadsheet noise like `7.0000000000000007E-2`), and the kJ column is emitted as kJ --
the loader converts.

Sparse cells are the trap, same as in BLS: empty cells are skipped entirely in the XML,
so values are placed by each cell's `r="C7"` column reference, never by document order.
The two workbooks are joined on the key in column A; the key sets and the food names are
verified to agree across them, so a reissue whose files drift fails here instead of
shifting every value by one food.

Usage:
    python tools/extract_afcd_csv.py \\
        "raw/afcd/AFCD Release 3 - Food Details.xlsx" \\
        "raw/afcd/AFCD Release 3 - Nutrient profiles.xlsx" \\
        raw/afcd/afcd.csv
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

# The xlsx reading machinery is extract_cofid_csv's, shared rather than copied:
# extract_frida_csv, extract_swiss_csv and extract_tca_csv all import the same
# three functions, so a fix to shared-string handling or to sheet resolution
# reaches every extractor at once.
from extract_cofid_csv import load_shared_strings, read_rows, sheet_files  # noqa: E402

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

# The two workbooks this tool understands, as published 2025-12 (docProps) and
# downloaded 2026-08-23. The sheet lists are pinned whole -- the Contents tab is the
# first sheet of every AFCD workbook -- so a reissue that adds or renames a sheet
# fails here instead of silently reading a shifted layout.
DETAILS_SHEETS = ["Contents", "Food details"]
PROFILES_SHEETS = ["Contents", "All solids & liquids per 100 g",
                   "Liquids only per 100 mL"]

# column index -> exact header text, on row 3 (rows 1-2 are the title and a blank).
# Only the columns the pipeline could use are pinned; every other column is
# unverified and dropped.
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

# Row 1 carries the release banner; pinning it catches a wrong file path at the
# top of the run instead of after the column checks.
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
    """All rows of one sheet as {column_index: text}, structure verified."""
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

    # The value columns must be numeric or empty. Anything else in a reissue --
    # a 'N', a 'Tr', a text note -- must fail here rather than silently parse
    # as a missing value.
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
