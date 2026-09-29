#!/usr/bin/env python3
"""Convert the TCA nutrient workbook to CSV."""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile

from extract_cofid_csv import load_shared_strings, read_rows, sheet_files  # noqa: E402

SHEET_NAMES = ("Componentes-Correspondência", "INSA - BDCA_v 7.1 - 2026",
               "Informação adicional")
DATA_SHEET = "INSA - BDCA_v 7.1 - 2026"

BASIS_BANNER = (
    "Valores\npor 100 g de parte edível com exceção do grupo Bebidas alcoólicas "
    "(nível 1) cujos valores são expressos por 100 ml de parte edível"
)

PINNED_COLUMNS = {
    1: ("Cod", "COD"),
    2: ("Nome do alimento", "NOME"),
    3: ("Nível 1 ", "NIVEL1"),
    6: ("Energia\n[kcal] ", "KCAL"),
    7: ("Energia\n[kJ] ", None),
    8: ("Lípidos\n[g]", "FAT"),
    9: ("Ácidos gordos saturados\n[g] ", "SAT_FAT"),
    14: ("Hidratos de carbono \n[g]", "CARBS"),
    15: ("Açúcares \n[g] ", "SUGAR"),
    18: ("Sal  \n[g]", None),
    19: ("Fibra  \n[g]", "FIBER"),
    20: ("Proteínas \n[g] ", "PROTEIN"),
    45: ("Sódio \n[mg]", "SODIUM"),
}

PINNED_CORRESPONDENCE = {
    "Energia\n[kcal] ": "ENERC",
    "Energia\n[kJ] ": "ENERC",
    "Lípidos\n[g]": "FAT",
    "Ácidos gordos saturados\n[g] ": "FASAT",
    "Hidratos de carbono \n[g]": "CHO",
    "Açúcares \n[g] ": "SUGAR",
    "Sal  \n[g]": "NACL",
    "Fibra  \n[g]": "FIBT",
    "Proteínas \n[g] ": "PROT",
    "Sódio \n[mg]": "NA",
}

OUTPUT_HEADERS = ["COD", "NOME", "NIVEL1", "KCAL", "FAT", "SAT_FAT", "CARBS",
                  "SUGAR", "FIBER", "PROTEIN", "SODIUM"]

def fail(message: str) -> None:
    print(f"  ! {message}", file=sys.stderr)
    sys.exit(1)

def extract(xlsx_path: str, csv_path: str) -> tuple[int, int]:
    with zipfile.ZipFile(xlsx_path) as zf:
        files = sheet_files(zf)
        if sorted(files) != sorted(SHEET_NAMES):
            fail(f"expected the 3-sheet TCA workbook, found {sorted(files)}")
        strings = load_shared_strings(zf)

        rows = list(read_rows(zf, files[DATA_SHEET], strings))
        banner = dict(rows[0][1])
        if banner.get(6) != BASIS_BANNER:
            fail(f"banner basis statement is {banner.get(6)!r}, expected {BASIS_BANNER!r}")
        header = dict(rows[1][1])
        for ci, (expected, _out) in PINNED_COLUMNS.items():
            actual = header.get(ci)
            if actual != expected:
                fail(f"data header column {ci} is {actual!r}, expected {expected!r}")

        corr_rows = list(read_rows(zf, files["Componentes-Correspondência"], strings))
        actual_corr: dict[str, str] = {}
        for rnum, cells in corr_rows[1:]:

            name = (cells.get(1) or "")
            code = (cells.get(2) or "").strip()
            if name in PINNED_CORRESPONDENCE:
                actual_corr[name] = code
        for name, expected in PINNED_CORRESPONDENCE.items():
            if actual_corr.get(name) != expected:
                fail(f"correspondence for {name!r} is {actual_corr.get(name)!r}, "
                     f"expected {expected!r}")

        data = [(rnum, cells) for rnum, cells in rows[2:]]
        codes: set[str] = set()
        non_numeric = 0
        for rnum, cells in data:
            code = (cells.get(1) or "").strip()
            if not code:
                fail(f"data row {rnum} has an empty Cod")
            if code in codes:
                fail(f"Cod {code!r} occurs twice")
            codes.add(code)
            for ci, (_h, _out) in PINNED_COLUMNS.items():
                if _out in (None, "COD", "NOME", "NIVEL1"):
                    continue
                v = (cells.get(ci) or "").strip()
                if v and not (
                    v[0].isdigit() or (v[0] == "-" and v[1:].isdigit())
                ):
                    non_numeric += 1

    written = 0
    with open(csv_path, "w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(OUTPUT_HEADERS)
        for _rnum, cells in data:
            writer.writerow([
                (cells.get(1) or "").strip(),
                cells.get(2, ""),
                cells.get(3, ""),
                cells.get(6, ""), cells.get(8, ""), cells.get(9, ""),
                cells.get(14, ""), cells.get(15, ""), cells.get(19, ""),
                cells.get(20, ""), cells.get(45, ""),
            ])
            written += 1
    if non_numeric:
        print(f"  {non_numeric} non-numeric value cells written through; the "
              f"loader's parser will decide each")
    return written, len(OUTPUT_HEADERS)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xlsx", help="path to insa_tca.xlsx")
    parser.add_argument("csv_out", help="path to write the extracted CSV to")
    args = parser.parse_args()
    rows, columns = extract(args.xlsx, args.csv_out)
    print(f"Wrote {rows:,} data rows x {columns} columns to {args.csv_out}")

if __name__ == "__main__":
    main()
