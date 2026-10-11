"""Independent expectations against the real parser, writer, Room DDL and FTS."""

import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from build_nutrition_db import Food, write_db
from nutrition_values import encode_nutrients, parse_nutrient

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = str(ROOT / "app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json")


class NutritionValuesTest(unittest.TestCase):
    def test_published_estimates_averages_weighted_and_unknown_types_keep_figures_and_codes(self):
        for code, expected in (
            ("BE", "BEST_ESTIMATE"),
            ("AV", "AVERAGE"),
            ("W", "WEIGHTED"),
            ("X", "UNSPECIFIED"),
        ):
            value = parse_nutrient("2,5", "g", "PROTEIN", code)
            self.assertEqual(2.5, value["value"])
            self.assertEqual([expected], value["qualifiers"])
            self.assertEqual(code, value["sourceValueType"])

    def test_comma_and_mg_are_explicit_and_keep_original_notation(self):
        value = parse_nutrient("400,0", "mg", "SODIUM")
        self.assertEqual(0.4, value["value"])
        self.assertEqual("400,0", value["sourceValue"])
        self.assertEqual("mg", value["sourceUnit"])

    def test_qualified_zero_does_not_establish_a_zero_detection_limit(self):
        value = parse_nutrient("0", "g", "FAT", "BL")
        self.assertEqual(0, value["value"])
        self.assertEqual(["BELOW_DETECTION"], value["qualifiers"])
        self.assertNotIn("upperBound", value)
        self.assertNotIn("upperBound", parse_nutrient("0", "mg", "SODIUM", "BQ"))

    def test_missing_trace_and_logical_zero_remain_distinct(self):
        missing = parse_nutrient("", "g", "FIBRE")
        trace = parse_nutrient("tr", "g", "FAT")
        zero = parse_nutrient("0", "g", "PROTEIN", "LZ")
        undecidable = parse_nutrient("0", "g", "FIBRE", "UD")
        self.assertNotIn("value", missing)
        self.assertNotIn("value", trace)
        self.assertEqual(["TRACE"], trace["qualifiers"])
        self.assertEqual(["LOGICAL_ZERO"], zero["qualifiers"])
        self.assertEqual(["UNDECIDABLE"], undecidable["qualifiers"])

    def test_explicit_bounds_preserve_strictness_and_units(self):
        value = parse_nutrient("<400", "mg", "SODIUM")
        self.assertEqual(0.4, value["value"])
        self.assertEqual(0.4, value["upperBound"])
        self.assertFalse(value["upperInclusive"])
        self.assertNotIn("upperInclusive", parse_nutrient("<=400", "mg", "SODIUM"))
        self.assertFalse(parse_nutrient(">0.2", "g", "FAT")["lowerInclusive"])

    def test_malformed_figures_units_qualifiers_and_metadata_fail(self):
        for raw in ("NaN", "Infinity", "-1", "1,2,3", "<<4", "<==4", "40 kcal", "1e9999"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                parse_nutrient(raw, "g", "FAT")
        for args in (
            ("1", "kg", "FAT"),
            ("1", "g", "CALORIES"),
            ("1", "g", "FAT", "made-up"),
            ("1", "g", "not-a-nutrient"),
            ("1", "g", "FAT", "LZ"),
        ):
            with self.subTest(args=args), self.assertRaises(ValueError):
                parse_nutrient(*args)
        for values in (
            {"FAT": {"value": float("nan")}},
            {"FAT": {"lowerBound": 2, "upperBound": 1}},
            {"FAT": {"value": 0, "qualifiers": ["made-up"]}},
            {"FAT": {"upperInclusive": False}},
            {"FAT": {"value": True}},
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                encode_nutrients(values)

    def test_real_writer_preserves_basis_qualifiers_missing_optional_id_and_fts(self):
        values = {
            "FAT": parse_nutrient("0", "g", "FAT", "BL"),
            "SODIUM": parse_nutrient("400", "mg", "SODIUM"),
            "FIBRE": parse_nutrient("", "g", "FIBRE"),
        }
        food = Food(
            999,
            "Synthetic volume food",
            None,
            None,
            40,
            2,
            0,
            3,
            sodium=0.4,
            nutrition_basis="ml",
            nutrition_metadata=values,
        )
        with tempfile.TemporaryDirectory() as folder:
            path = str(Path(folder) / "source.db")
            write_db(path, [food], SCHEMA, 18)
            with closing(sqlite3.connect(path)) as db:
                row = db.execute(
                    "SELECT id,nutrition_basis,kcal_100g,fat_100g,sodium_100g,fiber_100g,nutrition_metadata FROM foods"
                ).fetchone()
                self.assertEqual((999, "ml", 40, 0, 0.4, None), row[:6])
                self.assertEqual(values, json.loads(row[6])["nutrients"])
                self.assertEqual(
                    [(999,)],
                    db.execute(
                        "SELECT rowid FROM food_search WHERE food_search MATCH 'synthetic'"
                    ).fetchall(),
                )
                self.assertEqual(8, db.execute("PRAGMA user_version").fetchone()[0])

    def test_old_schema_cannot_silently_lose_volume_semantics(self):
        food = Food(999, "Synthetic volume food", None, None, 40, 2, 0, 3, nutrition_basis="ml")
        with tempfile.TemporaryDirectory() as folder, self.assertRaises(ValueError):
            write_db(str(Path(folder) / "source.db"), [food], str(Path(SCHEMA).with_name("7.json")))

    def test_writer_rejects_disagreement_instead_of_clamping_a_source_value(self):
        food = Food(
            999,
            "Synthetic food",
            None,
            None,
            40,
            2,
            0,
            3,
            nutrition_metadata={"FAT": parse_nutrient("1", "g", "FAT")},
        )
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "source.db"
            # Build a real valid catalogue, then prove rejected input cannot erase it.
            write_db(str(path), [Food(1, "Existing food", None, None, 10, 1, 0, 1)], SCHEMA)
            before = path.read_bytes()
            with self.assertRaises(ValueError):
                write_db(str(path), [food], SCHEMA)
            self.assertEqual(before, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
