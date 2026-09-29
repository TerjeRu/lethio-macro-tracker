#!/usr/bin/env python3
"""Convert the Frida nutrient workbook to CSV."""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile

from extract_cofid_csv import load_shared_strings, read_rows, sheet_files  # noqa: E402

SHEET_NAMES = (
    "Data_Normalised", "Data_Table", "Food", "FoodGroup", "Parameter", "Readme", "Source",
)

PINNED_PARAMETERS: dict[str, tuple[str, str]] = {
    "356": ("Energy (kcal)", "kcal/100 g"),
    "137": ("Energy (kJ)", "kJ/100g"),
    "359": ("Energy, labelling (kcal)", "kcal/100 g"),
    "316": ("Energy, labelling (kJ)", "kJ/100g"),
    "218": ("Protein", "g/100g"),
    "421": ("Protein from Amino Acids", "g/100g"),
    "317": ("Protein, labeling", "g/100g"),
    "172": ("Available carbohydrates", "g/100g"),
    "170": ("Carbohydrate by difference", "g/100g"),
    "318": ("Available carbohydrate, labelling", "g/100g"),
    "245": ("Sum sugars", "g/100g"),
    "417": ("Added Sugar", "g/100g"),
    "418": ("Free Sugars", "g/100g"),
    "168": ("Dietary fibre", "g/100g"),
    "141": ("Fat", "g/100g"),

    "248": ("Sum saturated fatty acids", "g/100g"),

    "247": ("Sum monounsaturated fatty acids", "g/100g"),
    "251": ("Sum polyunsaturated fatty acids", "g/100g"),
    "261": ("Sum trans fatty acids", "g/100g"),
    "340": ("Sum fatty acids", "g/100g"),
    "201": ("Sodium", "mg/100g"),
    "327": ("Salt labelling", "g/100g"),
}

MAPPED_PARAMETERS: dict[str, str] = {
    "356": "KCAL",
    "218": "PROTEIN",
    "172": "CARBS",
    "168": "FIBRE",
    "141": "FAT",
    "248": "SAT_FAT",
    "245": "SUGAR",
    "201": "SODIUM",
}

OUTPUT_HEADERS = ["FoodID", "FoodName", "FodevareNavn", "KCAL", "PROTEIN", "FAT",
                  "SAT_FAT", "CARBS", "FIBRE", "SUGAR", "SODIUM"]

DATA_NORMALISED_HEADERS = (
    "FoodID", "FødevareNavn", "FoodName", "ParameterID", "ParameterNavn",
    "ParameterName", "SortKey", "ResVal", "Min", "Max", "Median",
    "NumberOfDeterminations", "Source", "SourceFood",
)

def fail(message: str) -> None:
    print(f"  ! {message}", file=sys.stderr)
    sys.exit(1)

def header_columns(cells: dict[int, str]) -> dict[str, int]:

    return {text: ci for ci, text in cells.items()}

def extract(xlsx_path: str, csv_path: str) -> tuple[int, int]:
    with zipfile.ZipFile(xlsx_path) as zf:
        files = sheet_files(zf)
        names = sorted(files)
        if names != sorted(SHEET_NAMES):
            fail(f"expected the 7-sheet Frida workbook, found {names}")
        for name in ("Data_Normalised", "Food", "Parameter"):
            if files[name] not in zf.namelist():
                fail(f"no {files[name]} in the workbook")

        strings = load_shared_strings(zf)

        param_rows = list(read_rows(zf, files["Parameter"], strings))
        pcols = header_columns(param_rows[0][1])
        need = {"ParameterID", "ParameterName", "Unit"}
        if not need <= set(pcols):
            fail(f"Parameter sheet lacks columns {sorted(need - set(pcols))}")
        actual: dict[str, tuple[str, str]] = {}
        for _rnum, cells in param_rows[1:]:
            pid = (cells.get(pcols["ParameterID"]) or "").strip()
            if pid in PINNED_PARAMETERS:
                actual[pid] = (
                    (cells.get(pcols["ParameterName"]) or "").strip(),
                    (cells.get(pcols["Unit"]) or "").strip(),
                )
        for pid, expected in PINNED_PARAMETERS.items():
            if actual.get(pid) != expected:
                fail(f"Parameter {pid} is {actual.get(pid)!r}, expected {expected!r}")

        dn_rows = list(read_rows(zf, files["Data_Normalised"], strings))
        dncols = header_columns(dn_rows[0][1])
        if tuple(sorted(dncols)) != tuple(sorted(DATA_NORMALISED_HEADERS)):
            fail(f"Data_Normalised header is {sorted(dncols)}, "
                 f"expected {sorted(DATA_NORMALISED_HEADERS)}")

        values: dict[tuple[str, str], str] = {}
        dn_ids: set[str] = set()
        for _rnum, cells in dn_rows[1:]:
            fid = (cells.get(dncols["FoodID"]) or "").strip()
            dn_ids.add(fid)
            pid = (cells.get(dncols["ParameterID"]) or "").strip()
            if pid in MAPPED_PARAMETERS:
                key = (fid, pid)
                if key in values:
                    fail(f"Data_Normalised carries {key} twice")
                values[key] = cells.get(dncols["ResVal"], "")

        food_rows = list(read_rows(zf, files["Food"], strings))
        fcols = header_columns(food_rows[0][1])
        if not {"FoodID", "FoodName", "FødevareNavn"} <= set(fcols):
            fail(f"Food sheet lacks columns {sorted({'FoodID','FoodName','FødevareNavn'} - set(fcols))}")
        names: dict[str, tuple[str, str]] = {}
        for _rnum, cells in food_rows[1:]:
            fid = (cells.get(fcols["FoodID"]) or "").strip()
            if fid in names:
                fail(f"Food sheet carries FoodID {fid!r} twice")
            names[fid] = (
                (cells.get(fcols["FoodName"]) or "").strip(),
                (cells.get(fcols["FødevareNavn"]) or "").strip(),
            )
        if set(names) != dn_ids:
            only_food = sorted(set(names) - dn_ids)
            only_dn = sorted(dn_ids - set(names))
            fail(f"Food sheet and Data_Normalised FoodIDs disagree: "
                 f"{len(only_food)} only in Food ({only_food[:5]}), "
                 f"{len(only_dn)} only in Data_Normalised ({only_dn[:5]})")

    written = 0
    with open(csv_path, "w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(OUTPUT_HEADERS)

        pid_by_col = {col: pid for pid, col in MAPPED_PARAMETERS.items()}
        for fid in names:
            row = [fid, names[fid][0], names[fid][1]]
            for col in OUTPUT_HEADERS[3:]:

                row.append(values.get((fid, pid_by_col[col]), ""))
            writer.writerow(row)
            written += 1
    return written, len(OUTPUT_HEADERS)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xlsx", help="path to FCDB_6.1_Dataset.xlsx")
    parser.add_argument("csv_out", help="path to write the extracted CSV to")
    args = parser.parse_args()
    rows, columns = extract(args.xlsx, args.csv_out)
    print(f"Wrote {rows:,} data rows x {columns} columns to {args.csv_out}")

if __name__ == "__main__":
    main()
