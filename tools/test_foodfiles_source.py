"""Exercise the FOODfiles reader with independent publisher-shaped synthetic records."""

import csv
import json
import sqlite3
import subprocess
import sys
from contextlib import closing
import tempfile
import unittest
from pathlib import Path

from foodfiles_source import SELECTED, food_id, read_foodfiles
from build_nutrition_db import load_foodfiles, write_db
from export_search_results import export
from verify_foodfiles_import import verify
from prepare_foodfiles_device_check import prepare

HEADER = "© Copyright Synthetic test publisher, 2024.\n"


class FoodfilesReaderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.name = "Milk ~ café, synthetic"
        self.names = [
            {
                "FoodID": "F1110",
                "Food Name": self.name,
                "Short Food Name": "Synthetic milk",
                "AlternativeNames": "Synthetic dairy drink",
            }
        ]
        self.defs = [
            {"Code": c, "Unit Code": unit, "Matrix Unit Code": "W"}
            for c, (_, unit, _) in SELECTED.items()
        ]
        self.values = []
        for code, value in zip(SELECTED, (240, 20, 5, 7, 1, 4, 2, 400)):
            unit = SELECTED[code][1]
            self.values.append(
                dict(
                    zip(
                        (
                            "FoodID",
                            "Component Identifier",
                            "Value",
                            "Unit Code",
                            "Matrix Unit Code",
                            "Value Type Code",
                            "Source Code",
                            "Acquisition Type Code",
                            "Method Type Code",
                            "Method Indicator Code",
                        ),
                        ("F1110", code, str(value), unit, "W", "BE", "SOURCE1", "D", "A", "MI123"),
                    )
                )
            )
        self.portions = [
            {
                "FoodID": "F1110",
                "Food Name": self.name,
                "CSM": "100 mL",
                "Measure": "101",
                "Density (g/cm3)": "1.01",
            },
            {
                "FoodID": "F1110",
                "Food Name": self.name,
                "CSM": "1 cup (250 mL)",
                "Measure": "253",
                "Density (g/cm3)": "1.01",
            },
        ]

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, rows):
        with (self.root / name).open("w", encoding="utf-8", newline="") as stream:
            stream.write(HEADER)
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter="~")
            writer.writeheader()
            writer.writerows(rows)

    def load(self):
        for name, rows in (
            ("Names", self.names),
            ("StandardCodes", self.defs),
            ("StandardDataFT", self.values),
            ("Csm", self.portions),
        ):
            self.write(name, rows)
        report = {}
        result = read_foodfiles(str(self.root / "StandardDataFT"), report)
        return result, report

    def test_published_energy_names_units_provenance_and_measured_portions(self):
        foods, report = self.load()
        f = foods[0]
        self.assertEqual(f["name"], self.name)
        self.assertEqual(f["kcal"], 240)
        self.assertEqual(f["sodium"], 0.4)
        self.assertEqual(f["nutrition_basis"], "g")
        self.assertEqual(f["density"], 1.01)
        self.assertIn(("1 cup (250 mL)", 253), f["servings"])
        self.assertIn(("100 mL", 101), f["servings"])
        origin = f["nutrition_metadata"]["SODIUM"]["sourceOrigin"]
        self.assertEqual(origin["foodId"], "F1110")
        self.assertEqual(origin["nutrientCode"], "NA")
        self.assertIn({"code": "Method Indicator Code", "reference": "MI123"}, origin["references"])
        self.assertEqual(report["quarantinedFoods"], {})

    def test_real_writer_and_export_find_both_source_name_cells_without_changing_display(self):
        self.load()
        foods = load_foodfiles(str(self.root / "StandardDataFT"))
        schema = (
            Path(__file__).resolve().parents[1]
            / "app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json"
        )
        target = self.root / "written.db"
        write_db(str(target), foods, str(schema))
        self.assertEqual(verify(str(self.root / "StandardDataFT"), str(target))["errors"], [])
        expectations = prepare(
            str(self.root / "StandardDataFT"), str(target), str(self.root / "device.json")
        )
        expected = expectations["foods"][0]
        self.assertEqual(expected["nutrients"]["SODIUM"]["value"], 0.4)
        self.assertEqual(expected["nutrients"]["CALORIES"]["value"], 240)
        self.assertIn({"label": "1 cup (250 mL)", "grams": 253}, expected["portions"])
        with closing(sqlite3.connect(target)) as db:
            row = db.execute(
                "SELECT name,kcal_100g,sodium_100g,nutrition_metadata,source_rank FROM foods"
            ).fetchone()
            self.assertEqual(row[:3], (self.name, 240, 0.4))
            self.assertEqual(row[4], 0)
            metadata = json.loads(row[3])["nutrients"]
            self.assertEqual(metadata["SODIUM"]["sourceValue"], "400")
            for query in ("dairy", "milk"):
                self.assertEqual(
                    db.execute(
                        "SELECT rowid FROM food_search WHERE food_search MATCH ?", (query,)
                    ).fetchall(),
                    [(food_id("F1110"),)],
                )
        fixture = self.root / "fixture.tsv"
        fixture.write_text("dairy\tNZ\ten\t.*\ndairy\tNZ\tfr\t.*\n", encoding="utf-8")
        # Actual exporter uses the same display-language guard as FoodDao; the joined search
        # alias must not become a user-facing name even with another UI language.
        export(target, fixture, self.root / "export.tsv", 30)
        self.assertIn(self.name, (self.root / "export.tsv").read_text(encoding="utf-8"))
        self.assertNotIn(
            "Synthetic dairy drink", (self.root / "export.tsv").read_text(encoding="utf-8")
        )
        with closing(sqlite3.connect(target)) as db:
            db.execute("UPDATE foods SET kcal_100g=999, density_g_per_ml=1.0, name='Changed name'")
            db.execute("UPDATE food_servings SET grams=252.5 WHERE label='1 cup (250 mL)'")
            db.commit()
        errors = verify(str(self.root / "StandardDataFT"), str(target))["errors"]
        self.assertIn("F1110: original name", errors)
        self.assertIn("F1110/ENERC_FSANZ2_KCAL: written figure", errors)
        self.assertIn("F1110: measured portions and reviewed cup omission", errors)
        self.assertIn("F1110: unique source density or unknown", errors)
        before = (self.root / "device.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "failed independent"):
            prepare(str(self.root / "StandardDataFT"), str(target), str(self.root / "device.json"))
        self.assertEqual((self.root / "device.json").read_bytes(), before)

    def test_selected_pair_does_not_switch_to_decoy_components(self):
        for code in ("ENERC", "CHOAVL"):
            self.defs.append(
                {
                    "Code": code,
                    "Unit Code": "kcal" if code == "ENERC" else "g",
                    "Matrix Unit Code": "W",
                }
            )
            self.values.append({**self.values[0], "Component Identifier": code, "Value": "888"})
        foods, _ = self.load()
        self.assertEqual((foods[0]["kcal"], foods[0]["carbs"]), (240, 7))
        self.values = [r for r in self.values if r["Component Identifier"] != "CHOAVL_FSANZ"]
        with self.assertRaisesRegex(ValueError, "missing selected component"):
            self.load()

    def test_missing_optional_and_undecidable_original_figure_are_distinct(self):
        fibre = next(r for r in self.values if r["Component Identifier"] == "FIBTG")
        fibre["Value Type Code"] = "UD"
        f = self.load()[0][0]
        self.assertEqual(f["nutrition_metadata"]["FIBRE"]["sourceValue"], "1")
        self.assertIn("UNDECIDABLE", f["nutrition_metadata"]["FIBRE"]["qualifiers"])
        self.values.remove(fibre)
        f = self.load()[0][0]
        self.assertIsNone(f["fiber"])
        self.assertNotIn("value", f["nutrition_metadata"]["FIBRE"])

    def test_qualified_numeric_zero_is_preserved_and_invalid_logical_zero_fails(self):
        self.values[2].update(Value="0", **{"Value Type Code": "BL"})
        self.values[6]["Value"] = "0"
        f = self.load()[0][0]
        self.assertEqual(f["fat"], 0)
        self.assertNotIn("upperBound", f["nutrition_metadata"]["FAT"])
        self.values[2].update(Value="2.6", **{"Value Type Code": "LZ"})
        with self.assertRaisesRegex(ValueError, "Logical zero"):
            self.load()

    def test_invalid_input_fails_without_cleanup(self):
        for key, value in (
            ("Value", "NaN"),
            ("Unit Code", "ml"),
            ("Matrix Unit Code", "V"),
            ("Value Type Code", "NEW"),
            ("Component Identifier", "UNKNOWN"),
            ("FoodID", "A999"),
        ):
            with self.subTest(key=key):
                original = self.values[0].copy()
                self.values[0][key] = value
                with self.assertRaises(ValueError):
                    self.load()
                self.values[0] = original
        self.values.append(self.values[0].copy())
        with self.assertRaisesRegex(ValueError, "duplicate selected"):
            self.load()

    def test_selected_beef_cup_is_independent_of_source_order(self):
        for row in self.names:
            row["FoodID"] = "M1148"
        for row in self.values:
            row["FoodID"] = "M1148"
        self.portions = [
            {
                "FoodID": "M1148",
                "Food Name": self.name,
                "CSM": "1 cup (250 mL) diced ",
                "Measure": str(m),
                "Density (g/cm3)": str(d),
            }
            for m, d in ((200, 0.8), (215, 0.86))
        ]
        first, report = self.load()
        self.portions.reverse()
        second, _ = self.load()
        self.assertEqual(first, second)
        self.assertEqual(first[0]["servings"], [("1 cup (250 mL) diced ", 215)])
        self.assertIsNone(first[0]["density"])
        self.assertEqual(report["omittedPortions"][0]["grams"], "200")
        self.portions[0]["Measure"] = "216"
        with self.assertRaisesRegex(ValueError, "unexpected ambiguous portion"):
            self.load()

    def test_new_ambiguous_portions_fail_instead_of_choosing_larger_globally(self):
        self.portions[1]["CSM"] = self.portions[0]["CSM"]
        with self.assertRaisesRegex(ValueError, "unexpected ambiguous portion"):
            self.load()

    def test_reviewed_tiny_portion_hidden_but_food_nutrition_and_other_portion_retained(self):
        for row in self.names + self.values + self.portions:
            row["FoodID"] = "P46"
        self.portions[0].update(CSM="1 teaspoon (5 mL)", Measure="0.3")
        foods, report = self.load()
        self.assertEqual(len(foods), 1)
        self.assertEqual(
            (foods[0]["name"], foods[0]["kcal"], foods[0]["sodium"]), (self.name, 240, 0.4)
        )
        self.assertEqual(foods[0]["servings"], [("1 cup (250 mL)", 253)])
        self.assertEqual(foods[0]["density"], 1.01)
        self.assertEqual(report["omittedPortions"][0]["grams"], "0.3")
        schema = (
            Path(__file__).resolve().parents[1]
            / "app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json"
        )
        target = self.root / "hidden.db"
        write_db(str(target), load_foodfiles(str(self.root / "StandardDataFT")), str(schema))
        self.assertEqual(verify(str(self.root / "StandardDataFT"), str(target))["errors"], [])
        with closing(sqlite3.connect(target)) as db:
            db.execute(
                "INSERT INTO food_servings(food_id,label,grams,is_default,sort_order) VALUES(?,?,?,0,1)",
                (food_id("P46"), "1 teaspoon (5 mL)", 0.3),
            )
            db.commit()
        self.assertIn(
            "P46: measured portions and reviewed cup omission",
            verify(str(self.root / "StandardDataFT"), str(target))["errors"],
        )
        self.portions[0]["Measure"] = "0.4"
        with self.assertRaisesRegex(ValueError, "hidden portion has changed"):
            self.load()

    def test_unreviewed_small_or_large_portion_fails(self):
        for mass in ("0.3", "2001"):
            self.portions[0]["Measure"] = mass
            with self.assertRaisesRegex(ValueError, "unreviewed portion outside"):
                self.load()

    def test_stable_ids_and_pinned_whole_food_quarantine(self):
        self.assertNotEqual(food_id("A11"), food_id("B11"))
        for bad in ("a11", "A01", "A0", "AA11", "A100000000"):
            with self.assertRaises(ValueError):
                food_id(bad)
        for row in self.names:
            row["FoodID"] = "B1035"
        for row in self.values:
            row["FoodID"] = "B1035"
        for row in self.portions:
            row["FoodID"] = "B1035"
        self.values[2].update(Value="2.6", **{"Value Type Code": "LZ"})
        foods, report = self.load()
        self.assertEqual(foods, [])
        self.assertEqual(set(report["quarantinedFoods"]), {"B1035"})
        self.values[2]["Value"] = "2.7"
        with self.assertRaisesRegex(ValueError, "quarantine has changed"):
            self.load()

    def test_invalid_and_requested_empty_cli_preserve_real_existing_outputs(self):
        self.load()
        repo = Path(__file__).resolve().parents[1]
        schema = repo / "app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json"
        full, seed, lexicon = (
            self.root / name for name in ("existing.db", "existing-seed.db", "existing-lexicon.txt")
        )
        for target in (full, seed):
            write_db(str(target), [], str(schema))
        lexicon.write_text("existing verified lexicon\n", encoding="utf-8")
        before = {p: p.read_bytes() for p in (full, seed, lexicon)}
        args = [
            sys.executable,
            str(repo / "tools/build_nutrition_db.py"),
            "--foodfiles",
            str(self.root / "StandardDataFT"),
            "--schema",
            str(schema),
            "--out",
            str(full),
            "--seed",
            str(seed),
            "--lexicon",
            str(lexicon),
        ]
        self.values[0]["Value"] = "NaN"
        self.write("StandardDataFT", self.values)
        result = subprocess.run(args, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        for name, rows in (
            ("Names", self.names),
            ("StandardDataFT", self.values),
            ("Csm", self.portions),
        ):
            with (self.root / name).open("w", encoding="utf-8", newline="") as stream:
                stream.write(HEADER)
                csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter="~").writeheader()
        result = subprocess.run(args, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"requested but produced 0 rows", result.stderr)
        self.assertEqual(before, {p: p.read_bytes() for p in before})


if __name__ == "__main__":
    unittest.main()
