"""Real loader, CLI and writer tests with independent synthetic publisher-shaped input."""

import csv
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from build_nutrition_db import load_nevo, write_db
from verify_nevo_import import verify

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json"
FIELDS = [
    "ENERCC (kcal)",
    "PROT (g)",
    "FAT (g)",
    "CHO (g)",
    "FIBT (g)",
    "SUGAR (g)",
    "FASAT (g)",
    "NA (mg)",
]
BASE = {
    "NEVO-versie/NEVO-version": "NEVO-Online 2025 9.0",
    "NEVO-code": "42",
    "Voedingsmiddelnaam/Dutch food name": 'Synthetisch | "eten" café',
    "Engelse naam/Food name": 'Synthetic | "food" café',
    "Hoeveelheid/Quantity": "per 100ml",
    "Synoniem": "Alias met/of kwalificatie",
    "Bevat sporen van/Contains traces of": "FAT",
    "Is verrijkt met/Is fortified with": "FIBT",
}
BASE.update(dict(zip(FIELDS, ["40", "2,5", "0", "3", "1,2", "", "0", "400"])))


def write_rows(path, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]), delimiter="|")
        writer.writeheader()
        writer.writerows(rows)


def fixture(folder, records=None):
    path = Path(folder) / "NEVO2025_v9.0.csv"
    rows = records or [dict(BASE)]
    write_rows(path, rows)
    definitions = [
        {"Nutrient-code": k.split(" ")[0], "Eenheid/Unit": k.split("(")[1][:-1]} for k in FIELDS
    ]
    # Real definitions repeat codes under multiple component groups.
    write_rows(
        path.with_name(path.stem + "_Nutrienten_Nutrients.csv"), definitions + definitions[:1]
    )
    details = []
    for r in rows:
        for k in FIELDS:
            if not r[k]:
                continue
            code, unit = k.split(" ")[0], k.split("(")[1][:-1]
            detail = {
                key: r[key]
                for key in (
                    "NEVO-versie/NEVO-version",
                    "NEVO-code",
                    "Voedingsmiddelnaam/Dutch food name",
                    "Engelse naam/Food name",
                    "Hoeveelheid/Quantity",
                )
            }
            detail.update(
                {
                    "Nutrient-code": code,
                    "Gehalte/Value": r[k],
                    "Eenheid/Unit": unit,
                    "Spoor / Verrijkt/Trace / Fortified": (
                        "TR" if code == "FAT" else "+" if code == "FIBT" else ""
                    ),
                    "Broncode/Source code": "A",
                    "Referentie/Reference": "First reference",
                }
            )
            details.append(detail)
    details.append(
        dict(
            details[0],
            **{"Broncode/Source code": "B", "Referentie/Reference": "Different reference"},
        )
    )
    write_rows(path.with_name(path.stem + "_Details.csv"), details)
    return path


class NevoSourceTest(unittest.TestCase):
    def test_independent_reconciliation_detects_changed_written_values_names_and_references(self):
        with tempfile.TemporaryDirectory() as folder:
            path = fixture(folder)
            dbpath = str(Path(folder) / "nutrition.db")
            write_db(dbpath, load_nevo(str(path)), str(SCHEMA))
            self.assertEqual([], verify(str(path), dbpath)["errors"])
            with closing(sqlite3.connect(dbpath)) as db:
                raw = db.execute("SELECT nutrition_metadata FROM foods").fetchone()[0]
                values = json.loads(raw)
                values["nutrients"]["CALORIES"]["sourceOrigin"]["references"].pop()
                db.execute(
                    "UPDATE foods SET name=?, sodium_100g=?, nutrition_metadata=?",
                    ("Changed food", 400, json.dumps(values)),
                )
                db.commit()
            errors = verify(str(path), dbpath)["errors"]
            self.assertIn("42: native name", errors)
            self.assertIn("42/NA: numeric figure", errors)
            self.assertIn("42/ENERCC: all provenance references", errors)

    def test_real_writer_unicode_quoting_units_qualifiers_missing_and_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            path = fixture(folder)
            foods = load_nevo(str(path))
            self.assertEqual(1, len(foods))
            dbpath = str(Path(folder) / "nutrition.db")
            write_db(dbpath, foods, str(SCHEMA))
            with closing(sqlite3.connect(dbpath)) as db:
                row = db.execute(
                    "SELECT id,name,lang,nutrition_basis,kcal_100g,protein_100g,fat_100g,sugar_100g,sodium_100g,density_g_per_ml,nutrition_metadata FROM foods"
                ).fetchone()
                self.assertEqual(
                    (
                        200_000_000_042,
                        BASE["Voedingsmiddelnaam/Dutch food name"],
                        "nl",
                        "ml",
                        40,
                        2.5,
                        0,
                        None,
                        0.4,
                        None,
                    ),
                    row[:10],
                )
                values = json.loads(row[10])["nutrients"]
                self.assertEqual(["TRACE"], values["FAT"]["qualifiers"])
                self.assertEqual("TR", values["FAT"]["sourceValueType"])
                self.assertEqual("+", values["FIBRE"]["sourceValueType"])
                self.assertNotIn("qualifiers", values["FIBRE"])
                self.assertEqual("400", values["SODIUM"]["sourceValue"])
                self.assertEqual("mg", values["SODIUM"]["sourceUnit"])
                self.assertNotIn("value", values["SUGAR"])
                self.assertEqual(
                    [
                        {"code": "A", "reference": "First reference"},
                        {"code": "B", "reference": "Different reference"},
                    ],
                    values["CALORIES"]["sourceOrigin"]["references"],
                )
                self.assertEqual(
                    [(200_000_000_042,)],
                    db.execute(
                        "SELECT rowid FROM food_search WHERE food_search MATCH 'synthetic'"
                    ).fetchall(),
                )
                self.assertEqual(0, db.execute("SELECT COUNT(*) FROM food_servings").fetchone()[0])

    def test_publisher_calories_are_preserved_without_four_four_nine_reconstruction(self):
        row = dict(
            BASE, **{"ENERCC (kcal)": "234", "PROT (g)": "0", "FAT (g)": "0", "CHO (g)": "0"}
        )
        with tempfile.TemporaryDirectory() as folder:
            foods = load_nevo(str(fixture(folder, [row])))
            self.assertEqual(234, foods[0].kcal)

    def test_identical_bilingual_name_is_preserved_once_without_redundant_alias(self):
        row = dict(BASE, **{"Engelse naam/Food name": BASE["Voedingsmiddelnaam/Dutch food name"]})
        with tempfile.TemporaryDirectory() as folder:
            food = load_nevo(str(fixture(folder, [row])))[0]
            self.assertEqual(BASE["Voedingsmiddelnaam/Dutch food name"], food.name)
            self.assertNotIn(("en", food.name), food.aliases)

    def test_generated_british_spelling_does_not_duplicate_native_name(self):
        row = dict(
            BASE,
            **{
                "Voedingsmiddelnaam/Dutch food name": "Gelatine",
                "Engelse naam/Food name": "Gelatin",
            },
        )
        with tempfile.TemporaryDirectory() as folder:
            food = load_nevo(str(fixture(folder, [row])))[0]
            self.assertIn(("en", "Gelatin"), food.aliases)
            self.assertNotIn(("en-GB", "Gelatine"), food.aliases)

    def test_reordering_wide_and_details_preserves_identity_and_all_references(self):
        row2 = dict(BASE, **{"NEVO-code": "7", "Hoeveelheid/Quantity": "per 100g"})
        with tempfile.TemporaryDirectory() as folder:
            path = fixture(folder, [dict(BASE), row2])
            first = load_nevo(str(path))
            write_rows(path, [row2, dict(BASE)])
            detailpath = path.with_name(path.stem + "_Details.csv")
            with detailpath.open(encoding="utf-8-sig", newline="") as f:
                details = list(csv.DictReader(f, delimiter="|"))
            write_rows(detailpath, list(reversed(details)))
            self.assertEqual(first, load_nevo(str(path)))
            self.assertEqual([200_000_000_007, 200_000_000_042], [f.id for f in first])

    def test_conflicting_duplicates_figures_units_flags_names_and_bases_fail(self):
        for key, newvalue in (
            ("Gehalte/Value", "99"),
            ("Eenheid/Unit", "kJ"),
            ("Spoor / Verrijkt/Trace / Fortified", "TR"),
            ("Voedingsmiddelnaam/Dutch food name", "Changed"),
            ("Hoeveelheid/Quantity", "per 100g"),
            ("Nutrient-code", "UNKNOWN"),
        ):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as folder:
                path = fixture(folder)
                details = path.with_name(path.stem + "_Details.csv")
                with details.open(encoding="utf-8-sig", newline="") as f:
                    rows = list(csv.DictReader(f, delimiter="|"))
                rows[-1][key] = newvalue
                write_rows(details, rows)
                with self.assertRaises(ValueError):
                    load_nevo(str(path))

    def test_duplicate_ids_missing_names_primary_fields_wrong_basis_version_and_numbers_fail(self):
        cases = [
            (None, None),
            ("NEVO-code", "0"),
            ("NEVO-code", "١"),
            ("Voedingsmiddelnaam/Dutch food name", ""),
            ("Engelse naam/Food name", ""),
            ("ENERCC (kcal)", ""),
            ("PROT (g)", "-1"),
            ("FAT (g)", "NaN"),
            ("Hoeveelheid/Quantity", "per serving"),
            ("NEVO-versie/NEVO-version", "next release"),
            ("Bevat sporen van/Contains traces of", "UNKNOWN"),
        ]
        for key, value in cases:
            with self.subTest(key=key, value=value), tempfile.TemporaryDirectory() as folder:
                row = dict(BASE)
                if key is not None:
                    row[key] = value
                path = fixture(folder, [row, dict(row)] if key is None else [row])
                with self.assertRaises(ValueError):
                    load_nevo(str(path))

    def test_missing_companions_headers_and_selected_details_fail(self):
        for operation in ("definitions", "details", "header", "selected-detail", "definition-unit"):
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as folder:
                path = fixture(folder)
                if operation in ("definitions", "details"):
                    suffix = (
                        "_Nutrienten_Nutrients.csv"
                        if operation == "definitions"
                        else "_Details.csv"
                    )
                    path.with_name(path.stem + suffix).unlink()
                    expected = FileNotFoundError
                else:
                    expected = ValueError
                    if operation == "header":
                        row = dict(BASE)
                        row.pop("NA (mg)")
                        write_rows(path, [row])
                    elif operation == "definition-unit":
                        d = path.with_name(path.stem + "_Nutrienten_Nutrients.csv")
                        with d.open(encoding="utf-8-sig", newline="") as f:
                            rows = list(csv.DictReader(f, delimiter="|"))
                        rows[-2]["Eenheid/Unit"] = "g"
                        write_rows(d, rows)
                    else:
                        d = path.with_name(path.stem + "_Details.csv")
                        with d.open(encoding="utf-8-sig", newline="") as f:
                            rows = list(csv.DictReader(f, delimiter="|"))
                        write_rows(d, [r for r in rows if r["Nutrient-code"] != "FAT"])
                with self.assertRaises(expected):
                    load_nevo(str(path))

    def test_cli_invalid_or_requested_empty_does_not_replace_valid_outputs(self):
        for empty in (False, True):
            with self.subTest(empty=empty), tempfile.TemporaryDirectory() as folder:
                path = fixture(folder)
                if empty:
                    text = path.read_text(encoding="utf-8-sig").splitlines()[0] + "\n"
                    path.write_text(text, encoding="utf-8-sig")
                    detail = path.with_name(path.stem + "_Details.csv")
                    detail.write_text(
                        detail.read_text(encoding="utf-8-sig").splitlines()[0] + "\n",
                        encoding="utf-8-sig",
                    )
                else:
                    path.with_name(path.stem + "_Details.csv").unlink()
                output, seed, lexicon = [
                    Path(folder) / n for n in ("full.db", "seed.db", "lexicon.txt")
                ]
                for target in (output, seed):
                    write_db(str(target), [], str(SCHEMA))
                lexicon.write_text("previous lexicon", encoding="utf-8")
                before = [p.read_bytes() for p in (output, seed, lexicon)]
                process = subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / "tools/build_nutrition_db.py"),
                        "--nevo",
                        str(path),
                        "--schema",
                        str(SCHEMA),
                        "--out",
                        str(output),
                        "--seed",
                        str(seed),
                        "--lexicon",
                        str(lexicon),
                    ],
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(0, process.returncode)
                if empty:
                    self.assertIn("requested but produced 0 rows: nevo", process.stderr)
                self.assertEqual(before, [p.read_bytes() for p in (output, seed, lexicon)])


if __name__ == "__main__":
    unittest.main()
