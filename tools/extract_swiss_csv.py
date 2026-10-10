#!/usr/bin/env python3
"""Join the four Swiss FCDB language workbooks into one CSV the pipeline can read.

Reads the four separately-published language editions of the Swiss Food Composition
Database (FSVO, version 7.1, 01.07.2026):

    Swiss_food_composition_database.xlsx                 (English)
    Schweizer_Nahrwertdatenbank.xlsx                     (German)
    Base_de_donnees_suisse_des_valeurs_nutritives.xlsx   (French)
    Banca_dati_svizzera_dei_valori_nutritivi.xlsx        (Italian)

and writes one UTF-8 CSV with the English name, the de/fr/it names and the eight
mapped nutrient values. Like `extract_frida_csv.py` it is not a
general-purpose xlsx reader: it verifies each file's sheet list, header row and
column layout, and fails loudly if a future reissue changes any of them.

The trap this extractor exists to defuse is the **row-order join**. Each language
file is sorted alphabetically *in its own language* -- row 4 is ID 414 ("Acciuga
sott'olio") in the Italian file and ID 10533 ("Agar Agar") in the English one -- so
joining by row position would attach the wrong name to every single food while
every aggregate count stayed correct. The join here is on the `ID` column (column
A), and the extractor asserts the four files carry the *same* ID set, in each
direction, before a single name is written.

Two further checks, because these are four separately-generated exports:

- The **empty-row check.** The English file carries 200 fully-empty trailing rows
  (rows 1220-1419 of 1419); the other three carry none. An empty-ID row is skipped
  only after verifying every one of its cells is empty -- an empty-ID row with a
  name in it would be data being silently dropped.
- The **value-agreement check.** The mapped nutrient values are read from the
  English file only, so a drift between the four exports would load values that
  contradict the other three languages. Every mapped cell is compared across the
  four files after normalising each language's marker vocabulary: `tr.` (EN/FR/IT)
  = `Sp.` (DE, "Spuren", traces), `n.d.` (EN) = `k.A.` (DE, "keine Angabe") =
  `n.i.` (FR, "non indiqué") = `nd` (IT). The 60 cells that differ across the
  languages in the 2026 snapshot are exactly this vocabulary and nothing else;
  the check pins that.

Layout, verified against the 2026 snapshot: row 1 is a title banner, row 2 blank,
**row 3 the header**, data from row 4. Columns 1-8 are `ID`, `ID V 4.0`,
`ID SwissFIR`, `Name` (translated), `Synonyms` (translated), `Category`
(translated), `Density` (translated), `Matrix unit` (translated); from column 9
the sheet is a repeating triple per nutrient: `<Name> (unit)`, `Derivation of
value` (translated), `Source` (translated). Energy appears as both kJ (column 9)
and kcal (column 12); kcal is read, kJ is pinned as its sibling. `Salt (NaCl)
(g)` (column 57) is pinned as a decoy -- it is NOT sodium, which is `Sodium (Na)
(mg)` in column 117.

Only the `Generic Foods` sheet is read. `Branded foods` holds 229 Swiss retail
products with no barcode, which is Open Food Facts' territory.

Sparse cells are the trap, same as in BLS: empty cells are skipped entirely in
the XML, so values are placed by each cell's `r="C7"` column reference, never by
document order.

Usage:
    python tools/extract_swiss_csv.py raw/swiss/swiss.csv
"""

from __future__ import annotations

import argparse
import csv
import sys
import zipfile

from extract_cofid_csv import load_shared_strings, read_rows, sheet_files  # noqa: E402

# The four editions this tool understands, as published 2026-08-23. Every entry is checked against the workbook, so a
# reissue that renames a sheet or moves a column fails here instead of producing a
# plausible-looking CSV with the values under the wrong names.
LANGS = {
    "EN": {
        "file": "Swiss_food_composition_database.xlsx",
        "sheets": ("Branded foods", "Generic Foods", "Nutrient codes", "Sources"),
        "generic": "Generic Foods",
        # header row 3, the first eight columns (the non-nutrient block)
        "header": {1: "ID", 2: "ID V 4.0", 3: "ID SwissFIR", 4: "Name",
                   5: "Synonyms", 6: "Category", 7: "Density", 8: "Matrix unit"},
        # the marker vocabulary for the value-agreement check
        "markers": {"trace": "tr.", "missing": "n.d."},
    },
    "DE": {
        "file": "Schweizer_Nahrwertdatenbank.xlsx",
        "sheets": ("Generische Lebensmittel", "Markenprodukte", "Nutrient Codes", "Quellen"),
        "generic": "Generische Lebensmittel",
        "header": {1: "ID", 2: "ID V 4.0", 3: "ID SwissFIR", 4: "Name",
                   5: "Synonyme", 6: "Kategorie", 7: "Dichte", 8: "Bezugseinheit"},
        "markers": {"trace": "Sp.", "missing": "k.A."},
    },
    "FR": {
        "file": "Base_de_donnees_suisse_des_valeurs_nutritives.xlsx",
        "sheets": ("Aliments génériques", "Nutrient codes", "Produits de marque", "Sources"),
        "generic": "Aliments génériques",
        "header": {1: "ID", 2: "ID V 4.0", 3: "ID SwissFIR", 4: "Nom",
                   5: "Synonymes", 6: "Catégorie", 7: "Densité", 8: "Unité de matrice"},
        "markers": {"trace": "tr.", "missing": "n.i."},
    },
    "IT": {
        "file": "Banca_dati_svizzera_dei_valori_nutritivi.xlsx",
        "sheets": ("Alimenti generici", "Fonti", "Nutrient codes", "Prodotti di marca"),
        "generic": "Alimenti generici",
        "header": {1: "ID", 2: "ID V 4.0", 3: "ID SwissFIR", 4: "Nome",
                   5: "Sinonimi", 6: "Categoria", 7: "Densità", 8: "Unità di riferimento"},
        "markers": {"trace": "tr.", "missing": "nd"},
    },
}

# column index -> header text of the nutrient block, pinned from the English file.
# The kJ and salt entries are pinned decoys, not emitted: reading the kJ column
# as kcal (or salt as sodium) is the exact defect this pin exists to prevent.
PINNED_NUTRIENT_HEADERS = {
    9: "Energy, kilojoules (kJ)",
    12: "Energy, kilocalories (kcal)",
    15: "Fat, total (g)",
    18: "Fatty acids, saturated (g)",
    42: "Carbohydrates, available (g)",
    45: "Sugars (g)",
    51: "Dietary fibres (g)",
    54: "Protein (g)",
    57: "Salt (NaCl) (g)",
    117: "Sodium (Na) (mg)",
}

# column index -> output column. The decoys map to nothing, on purpose.
MAPPED_COLUMNS = {
    12: "KCAL",
    15: "FAT",
    18: "SAT_FAT",
    42: "CARBS",
    45: "SUGAR",
    51: "FIBER",
    54: "PROTEIN",
    117: "SODIUM",
}

OUTPUT_HEADERS = ["ID", "NAME", "NAME_DE", "NAME_FR", "NAME_IT",
                  "KCAL", "FAT", "SAT_FAT", "CARBS", "SUGAR", "FIBER",
                  "PROTEIN", "SODIUM"]


def fail(message: str) -> None:
    print(f"  ! {message}", file=sys.stderr)
    sys.exit(1)


def cell_norm(lang: str, raw: str) -> str:
    """One cell normalised for the cross-language agreement check: the numeric
    text verbatim (spreadsheet noise included), a below-limit '<x' verbatim, or
    the language's marker for trace/missing."""
    s = raw.strip()
    if not s:
        return ""
    markers = LANGS[lang]["markers"]
    if s == markers["trace"]:
        return "TRACE"
    if s == markers["missing"]:
        return "MISSING"
    if s[0] == "<" or (s[0] == "-" and s[1:].isdigit()) or s[0].isdigit() or s[0] == ".":
        return s
    fail(f"unrecognised value cell {s!r} in the {lang} file")


def read_generic(path: str, lang: str) -> dict[str, dict]:
    """ID -> {4: name, mapped columns: raw text}. Empty-ID rows are skipped
    after verifying they are fully empty."""
    with zipfile.ZipFile(path) as zf:
        files = sheet_files(zf)
        names = sorted(files)
        spec = LANGS[lang]
        if names != sorted(spec["sheets"]):
            fail(f"{lang} workbook holds sheets {names}, expected {sorted(spec['sheets'])}")
        strings = load_shared_strings(zf)
        rows = list(read_rows(zf, files[spec["generic"]], strings))

    header = dict(rows[2][1])
    for ci, expected in spec["header"].items():
        actual = header.get(ci)
        if actual != expected:
            fail(f"{lang} header column {ci} is {actual!r}, expected {expected!r}")

    if lang == "EN":
        # The nutrient block is read from the English file only, so its headers
        # are pinned here: a reissue that renames a nutrient or moves a column
        # fails before any value is written under the wrong name.
        for ci, expected in PINNED_NUTRIENT_HEADERS.items():
            actual = header.get(ci)
            if actual != expected:
                fail(f"EN header column {ci} is {actual!r}, expected {expected!r}")

    data: dict[str, dict] = {}
    empties = 0
    for rnum, cells in rows[3:]:
        fid = (cells.get(1) or "").strip()
        if not fid:
            nonempty = {ci: v for ci, v in cells.items() if (v or "").strip()}
            if nonempty:
                fail(f"{lang} row {rnum} has an empty ID but carries cells {nonempty}")
            empties += 1
            continue
        if fid in data:
            fail(f"{lang} carries ID {fid!r} twice")
        data[fid] = cells
    if empties:
        print(f"  {lang}: {empties} fully-empty trailing rows skipped")
    return data


def extract(directory: str, csv_path: str) -> tuple[int, int]:
    tables = {
        lang: read_generic(f"{directory}/{spec['file']}", lang)
        for lang, spec in LANGS.items()
    }

    # The row-order-join trap's hard check: the four exports must name the same
    # foods. A food present in one language and missing from another must not
    # silently lose a name, so it fails here rather than half-way through the CSV.
    en_ids = set(tables["EN"])
    for lang in ("DE", "FR", "IT"):
        only_en = en_ids - set(tables[lang])
        only_l = set(tables[lang]) - en_ids
        if only_en or only_l:
            fail(f"ID sets disagree: {len(only_en)} only in EN {sorted(only_en)[:5]}, "
                 f"{len(only_l)} only in {lang} {sorted(only_l)[:5]}")

    # The mapped values are read from the English file only, so the other three
    # exports must agree with it cell by cell (marker vocabulary normalised).
    for fid in sorted(en_ids):
        for ci in MAPPED_COLUMNS:
            canon = cell_norm("EN", tables["EN"][fid].get(ci, ""))
            for lang in ("DE", "FR", "IT"):
                if cell_norm(lang, tables[lang][fid].get(ci, "")) != canon:
                    fail(f"id {fid} column {ci} disagrees: EN "
                         f"{tables['EN'][fid].get(ci, '')!r} vs {lang} "
                         f"{tables[lang][fid].get(ci, '')!r}")

    written = 0
    with open(csv_path, "w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(OUTPUT_HEADERS)
        # Iterate the English file's row order so the CSV is deterministic.
        for fid, cells in tables["EN"].items():
            writer.writerow([
                fid, cells.get(4, ""),
                tables["DE"][fid].get(4, ""),
                tables["FR"][fid].get(4, ""),
                tables["IT"][fid].get(4, ""),
                cells.get(12, ""), cells.get(15, ""), cells.get(18, ""),
                cells.get(42, ""), cells.get(45, ""), cells.get(51, ""),
                cells.get(54, ""), cells.get(117, ""),
            ])
            written += 1
    return written, len(OUTPUT_HEADERS)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_out", help="path to write the joined CSV to")
    parser.add_argument(
        "--dir", default="raw/swiss",
        help="directory holding the four language workbooks",
    )
    args = parser.parse_args()
    rows, columns = extract(args.dir, args.csv_out)
    print(f"Wrote {rows:,} data rows x {columns} columns to {args.csv_out}")


if __name__ == "__main__":
    main()
