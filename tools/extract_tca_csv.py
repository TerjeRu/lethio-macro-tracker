#!/usr/bin/env python3
"""Extract the Portuguese TCA nutrient workbook to a CSV the pipeline can read.

Reads one known file -- `insa_tca.xlsx` (Instituto Nacional de Saúde Doutor Ricardo
Jorge, Tabela da Composição de Alimentos, version 7.1, 2026) -- and writes a UTF-8
CSV. Like `extract_swiss_csv.py` it is not a general-purpose xlsx
reader: it verifies the sheet list, the banner, the header row and the component
correspondence, and fails loudly if a future reissue changes any of them.

The workbook's three sheets are `INSA - BDCA_v 7.1 - 2026` (the data),
`Componentes-Correspondência` (component -> EuroFIR/INFOODS codes) and
`Informação adicional` (the terms of use). The data sheet's row 1 is a banner
whose sixth cell states the basis rule -- **values are per 100 g of edible
portion, except the group `Bebidas alcoólicas` (alcoholic beverages), whose
values are per 100 ml** -- and that sentence is pinned verbatim, because it is
the only place the per-100-ml exception is documented. Row 2 is the header, data
starts at row 3.

The mapped columns, each pinned by its header text (the component
correspondence sheet is verified to agree, so a reissue that renames a column
fails here instead of reading it from the wrong place):

| field   | column | header (verbatim)            | correspondence |
|---|---|---|---|
| kcal    | 6      | `Energia\n[kcal] `           | ENERC          |
| (kJ)    | 7      | `Energia\n[kJ] ` -- decoy    | ENERC          |
| fat     | 8      | `Lípidos\n[g]`               | FAT            |
| sat_fat | 9      | `Ácidos gordos saturados\n[g] ` | FASAT       |
| carbs   | 14     | `Hidratos de carbono \n[g]`  | CHO / CHOAVL   |
| sugar   | 15     | `Açúcares \n[g] `            | SUGAR          |
| (salt)  | 18     | `Sal  \n[g]` -- decoy        | NACL           |
| fiber   | 19     | `Fibra  \n[g]`               | FIBT           |
| protein | 20     | `Proteínas \n[g] `           | PROT           |
| sodium  | 45     | `Sódio \n[mg]`               | NA             |

`Hidratos de carbono` is available carbohydrates: it sums sugars + starch + the
oligosaccharide column exactly on sampled rows (Fibra is separate). `Sal` is
pinned as a decoy -- it is NOT sodium, which is `Sódio` in column 45 (mg/100 g).
The `Nível 1` group column is emitted so the loader can reject the alcoholic-
beverages rows by their declared per-100-ml basis rather than by guessing.

Sparse cells are the trap, same as in BLS: empty cells are skipped entirely in
the XML, so values are placed by each cell's `r="C7"` column reference, never by
document order.

Usage:
    python tools/extract_tca_csv.py raw/tca/insa_tca.xlsx \\
        raw/tca/tca.csv
"""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile

from extract_cofid_csv import load_shared_strings, read_rows, sheet_files  # noqa: E402

SHEET_NAMES = ("Componentes-Correspondência", "INSA - BDCA_v 7.1 - 2026",
               "Informação adicional")
DATA_SHEET = "INSA - BDCA_v 7.1 - 2026"

# The basis statement, pinned verbatim from the banner row (row 1, cell 6). It is
# the only place the workbook documents that alcoholic beverages are per 100 ml
# while everything else is per 100 g.
BASIS_BANNER = (
    "Valores\npor 100 g de parte edível com exceção do grupo Bebidas alcoólicas "
    "(nível 1) cujos valores são expressos por 100 ml de parte edível"
)

# column index -> (header text, output column or None for a pinned decoy)
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

# component name (verbatim) -> EuroFIR code, from the correspondence sheet. The
# mapped rows are what the loader reads; the kJ and salt decoys are pinned so a
# reissue that relabels them cannot be mistaken for the mapped one.
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

        # --- banner + header on the data sheet --------------------------------
        rows = list(read_rows(zf, files[DATA_SHEET], strings))
        banner = dict(rows[0][1])
        if banner.get(6) != BASIS_BANNER:
            fail(f"banner basis statement is {banner.get(6)!r}, expected {BASIS_BANNER!r}")
        header = dict(rows[1][1])
        for ci, (expected, _out) in PINNED_COLUMNS.items():
            actual = header.get(ci)
            if actual != expected:
                fail(f"data header column {ci} is {actual!r}, expected {expected!r}")

        # --- component correspondence agrees with the data sheet --------------
        corr_rows = list(read_rows(zf, files["Componentes-Correspondência"], strings))
        actual_corr: dict[str, str] = {}
        for rnum, cells in corr_rows[1:]:
            # Verbatim, not stripped: several pinned names end in a space, and
            # stripping it would make the pinned name unreachable.
            name = (cells.get(1) or "")
            code = (cells.get(2) or "").strip()
            if name in PINNED_CORRESPONDENCE:
                actual_corr[name] = code
        for name, expected in PINNED_CORRESPONDENCE.items():
            if actual_corr.get(name) != expected:
                fail(f"correspondence for {name!r} is {actual_corr.get(name)!r}, "
                     f"expected {expected!r}")

        # --- data rows --------------------------------------------------------
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
