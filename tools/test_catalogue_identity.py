"""Exercise the real catalogue writer, FTS rebuild, and barcode/source identity boundaries."""

from pathlib import Path
import sqlite3
import tempfile
import unittest

from audit_nutrition_db import CHECKS
from build_nutrition_db import (
    Food,
    OFF_ID_BASE,
    branded_display_groups,
    generic_display_rows,
    group_display_duplicates,
    select_catalogue,
    write_db,
    prepare_generic_catalogue,
    rank_name,
    basic_rank,
    BASIC_RANK_WHOLE,
    BASIC_RANK_DERIVATIVE,
)
from export_search_results import export

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = str(ROOT / "app/schemas/com.lethio.macros.data.food.FoodDatabase/7.json")


def packaged(code, kcal, carbs, *, popularity=1, serving=100):
    return Food(
        id=OFF_ID_BASE + int(code),
        name="Same drink",
        brand="Example",
        barcode=code,
        kcal=kcal,
        protein=0,
        fat=0,
        carbs=carbs,
        popularity=popularity,
        servings=[("serving", serving)],
    )


class CatalogueIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "catalogue.db"

    def open(self, foods, search_ids):
        write_db(str(self.path), foods, SCHEMA, 17, search_ids=search_ids)
        db = sqlite3.connect(self.path)
        self.addCleanup(db.close)
        return db

    def test_barcode_keeps_own_nutrition_and_serving_but_search_has_one_winner(self):
        winner = packaged("5449000028921", 50, 12.5, popularity=10, serving=250)
        sibling = packaged("42105220", 96, 24, serving=330)
        foods, search_ids = select_catalogue([], [], group_display_duplicates([winner, sibling]), 1)
        db = self.open(foods, search_ids)
        records = db.execute(
            "SELECT b.barcode,f.id,f.kcal_100g,f.carbs_100g,s.grams FROM food_barcodes b "
            "JOIN foods f ON f.id=b.food_id JOIN food_servings s ON s.food_id=f.id ORDER BY b.barcode"
        ).fetchall()
        self.assertEqual(
            records,
            [
                ("42105220", sibling.id, 96, 24, 330),
                ("5449000028921", winner.id, 50, 12.5, 250),
            ],
        )
        for _ in range(2):
            self.assertEqual(
                db.execute(
                    "SELECT rowid FROM food_search WHERE food_search MATCH 'drink'"
                ).fetchall(),
                [(winner.id,)],
            )
            db.execute("INSERT INTO food_search(food_search) VALUES('rebuild')")
            db.execute("INSERT INTO food_search(food_search,rank) VALUES('integrity-check',1)")
        # A saved reference can still open the hidden record directly, independent of FTS.
        self.assertEqual(
            db.execute("SELECT barcode FROM foods WHERE id=?", (sibling.id,)).fetchone(),
            ("42105220",),
        )

    def test_catalogue_limit_keeps_selected_groups_members(self):
        a = packaged("5449000028921", 50, 12.5, popularity=10)
        b = packaged("42105220", 96, 24)
        c = packaged("3017620422003", 200, 50)
        foods, search_ids = select_catalogue([], [], group_display_duplicates([a, b, c]), 1)
        self.assertEqual({f.id for f in foods}, {a.id, b.id})
        self.assertEqual(search_ids, {a.id})

    def test_scan_floor_applies_to_winner_without_losing_low_scan_siblings(self):
        a = packaged("5449000028921", 50, 12.5, popularity=10)
        b = packaged("42105220", 96, 24, popularity=0)
        c = packaged("3017620422003", 200, 50, popularity=1)
        groups = branded_display_groups([b, c, a], min_scans=2)
        foods, search_ids = select_catalogue([], [], groups, 10)
        self.assertEqual({f.id for f in foods}, {a.id, b.id})
        self.assertEqual(search_ids, {a.id})

    def test_national_sources_keep_their_own_records_and_aliases(self):
        us = Food(
            200000001,
            "Milk",
            None,
            None,
            50,
            3,
            2,
            5,
            source="usda",
            lang="en",
            carbs_convention="by_difference",
            quality=10,
            aliases=[("es", "Leche")],
        )
        uk = Food(
            600000001,
            "Milk",
            None,
            None,
            65,
            3.5,
            4,
            4,
            source="cofid",
            lang="en",
            carbs_convention="available_monosaccharide",
            quality=5,
        )
        search, preferred = prepare_generic_catalogue([us, uk])
        foods, search_ids = select_catalogue([us, uk], search, [], 10)
        db = self.open(foods, search_ids)
        self.assertEqual(
            db.execute(
                "SELECT source,kcal_100g FROM foods WHERE id IN "
                "(SELECT rowid FROM food_search WHERE food_search MATCH 'milk') ORDER BY source"
            ).fetchall(),
            [("cofid", 65), ("usda", 50)],
        )
        self.assertEqual(preferred, {us.id})
        self.assertEqual(
            db.execute("SELECT food_id FROM food_aliases WHERE lang=?", ("es",)).fetchall(),
            [(us.id,)],
        )
        sql, budget, _ = CHECKS["duplicate search rows within a source (name, brand, energy)"]
        self.assertEqual(db.execute(sql).fetchone()[0], budget)
        db.execute("UPDATE foods SET source='usda' WHERE id=?", (uk.id,))
        self.assertGreater(db.execute(sql).fetchone()[0], budget)

    def test_equal_matches_preserve_the_representative_but_exact_restored_matches_win(self):
        established = Food(
            1,
            "Grain, mature seeds, dry",
            None,
            None,
            350,
            10,
            2,
            70,
            source="cnf",
            lang="en",
            basic=1,
        )
        restored = Food(
            2, "Grain, sprouted, raw", None, None, 100, 5, 1, 20, source="cnf", lang="en", basic=0
        )
        exact = Food(3, "Grain", None, None, 350, 10, 2, 70, source="cnf", lang="en", basic=1)
        fixture = self.path.with_suffix(".fixture.tsv")
        fixture.write_text("grain\tCA\ten\tgrain\t\n")
        output = self.path.with_suffix(".tsv")
        write_db(
            str(self.path),
            [established, restored],
            SCHEMA,
            18,
            preferred_search_ids={established.id},
        )
        export(self.path, fixture, output, 30)
        self.assertEqual(
            output.read_text().splitlines()[0], "grain\tCA\ten\tGrain, mature seeds, dry"
        )
        write_db(
            str(self.path),
            [established, restored, exact],
            SCHEMA,
            18,
            preferred_search_ids={established.id},
        )
        export(self.path, fixture, output, 30)
        self.assertEqual(output.read_text().splitlines()[0], "grain\tCA\ten\tGrain")

    def test_foodfiles_preference_is_derived_apart_from_established_tables(self):
        # FOODfiles names collide with USDA/CoFID/AFCD/CNF names. Derived together, its
        # more popular row would take the established representative's equal-match preference.
        us = Food(
            200000001, "Milk, whole", None, None, 60, 3, 3, 5, source="usda", lang="en", quality=1
        )
        nz = Food(
            210000001,
            "Milk, whole",
            None,
            None,
            66,
            3,
            4,
            5,
            source="foodfiles",
            lang="en",
            quality=99,
        )
        _, preferred = prepare_generic_catalogue([us, nz])
        self.assertEqual(preferred, {us.id, nz.id})

    def test_nevo_is_ranked_by_its_english_name(self):
        canned = Food(
            1,
            "Wortel blik/glas",
            None,
            None,
            20,
            1,
            0,
            4,
            source="nevo",
            lang="nl",
            aliases=[("en", "Carrot tinned")],
        )
        raw = Food(
            2,
            "Wortel rauw gem",
            None,
            None,
            30,
            1,
            0,
            6,
            source="nevo",
            lang="nl",
            aliases=[("en", "Carrot raw av")],
        )
        starch = Food(
            3,
            "Aardappelzetmeel",
            None,
            None,
            350,
            0,
            0,
            85,
            source="nevo",
            lang="nl",
            aliases=[("en", "Potato starch")],
        )
        self.assertEqual(rank_name(raw), "Carrot raw av")
        self.assertEqual(basic_rank(rank_name(raw), "nevo", None), BASIC_RANK_WHOLE)
        self.assertEqual(basic_rank(rank_name(starch), "nevo", None), BASIC_RANK_DERIVATIVE)
        self.assertLess(
            basic_rank(rank_name(raw), "nevo", None), basic_rank(rank_name(canned), "nevo", None)
        )
        # Other tables still rank `name`, which is English until native-name promotion.
        other = Food(
            4,
            "Carrot, raw",
            None,
            None,
            30,
            1,
            0,
            6,
            source="usda",
            lang="en",
            aliases=[("es", "Zanahoria cruda")],
        )
        self.assertEqual(rank_name(other), "Carrot, raw")

    def test_whole_milk_as_an_ingredient_does_not_make_a_food_whole(self):
        # "whole" inside "...with whole milk" names the ingredient, not the food.
        for name in (
            "Whole milk",
            "Whole milk, pasteurized",
            "Milk, whole",
            "Milk, whole milk, 3.5% fat, boiled",
            "Yogurt, plain, whole milk",
        ):
            self.assertEqual(basic_rank(name, "usda", None), BASIC_RANK_WHOLE, name)
        for name in (
            "Puddings, rice, dry mix, prepared with whole milk",
            "Whole milk chocolate",
            "Yoghurt Bulgarian whole milk",
            "Coffee latte, prepared with whole milk",
        ):
            self.assertNotEqual(basic_rank(name, "usda", None), BASIC_RANK_WHOLE, name)

    def lead_word_export(self, source, country, language, query, names):
        # A name may carry `|alias` -- NEVO's rice noodles reach `rijst gekookt` only through
        # their alias "rijstnoedels gekookt", exactly as in the real table.
        foods = []
        for i, entry in enumerate(names):
            name, _, alias = entry.partition("|")
            foods.append(
                Food(
                    i + 1,
                    name,
                    None,
                    None,
                    100,
                    1,
                    1,
                    10,
                    source=source,
                    lang=language,
                    aliases=[(language, alias)] if alias else [],
                )
            )
        write_db(str(self.path), foods, SCHEMA, 18, search_ids={f.id for f in foods})
        fixture = self.path.with_suffix(".fixture.tsv")
        fixture.write_text(f"{query}\t{country}\t{language}\tx\t\n", encoding="utf-8")
        output = self.path.with_suffix(".tsv")
        export(self.path, fixture, output, 30)
        return [line.split("\t")[3] for line in output.read_text(encoding="utf-8").splitlines()]

    def test_lead_word_rule_names_the_food_on_new_tables_only(self):
        dutch = [
            "Mihoen gekookt|Rijstnoedels gekookt",
            "Rijst witte gekookt",
            "Pasta witte m ei gekookt",
        ]
        self.assertEqual(
            self.lead_word_export("nevo", "NL", "nl", "rijst gekookt", dutch)[0],
            "Rijst witte gekookt",
        )
        # A trailing -en on the name's head still names the food the reader typed.
        self.assertEqual(
            self.lead_word_export(
                "nevo", "NL", "nl", "aardappel rauw", ["Aardappel zoete rauw", "Aardappelen rauw"]
            )[0],
            "Aardappelen rauw",
        )
        nz = [
            "Cabbage, red, stir-fried with olive oil",
            "Oil, olive, composite",
            "Olive, green, plain, in oil",
        ]
        self.assertEqual(
            self.lead_word_export("foodfiles", "NZ", "en", "olive oil", nz)[0],
            "Oil, olive, composite",
        )
        # Naming the food AND containing the phrase beats only naming it.
        self.assertEqual(
            self.lead_word_export(
                "foodfiles",
                "NZ",
                "en",
                "sugar white",
                ["White chocolate, plain, sugar added", "Sugar, white"],
            )[0],
            "Sugar, white",
        )
        # Identical rows on an established table keep the established ladder: the phrase match.
        self.assertEqual(
            self.lead_word_export("cnf", "CA", "en", "olive oil", nz)[0],
            "Cabbage, red, stir-fried with olive oil",
        )
        self.assertEqual(
            self.lead_word_export("cnf", "CA", "en", "rijst gekookt", dutch)[0], "Mihoen gekookt"
        )

    def test_generic_display_collision_does_not_discard_a_package_barcode(self):
        generic = Food(
            200000001,
            "Same drink",
            "Example",
            None,
            60,
            0,
            0,
            15,
            source="usda",
            lang="en",
            carbs_convention="by_difference",
        )
        package = packaged("42105220", 96, 24)
        foods, search_ids = select_catalogue(
            [generic], [generic], group_display_duplicates([package]), 1
        )
        db = self.open(foods, search_ids)
        self.assertEqual(
            db.execute("SELECT rowid FROM food_search WHERE food_search MATCH 'drink'").fetchall(),
            [(generic.id,)],
        )
        self.assertEqual(
            db.execute("SELECT food_id FROM food_barcodes").fetchall(), [(package.id,)]
        )

    def test_audit_rejects_redirecting_a_barcode_to_a_sibling(self):
        a = packaged("5449000028921", 50, 12.5, popularity=10)
        b = packaged("42105220", 96, 24)
        db = self.open([a, b], {a.id})
        sql, budget, _ = CHECKS["barcode resolves to a different package's record"]
        self.assertEqual(db.execute(sql).fetchone()[0], budget)
        db.execute("UPDATE food_barcodes SET food_id=? WHERE barcode=?", (a.id, b.barcode))
        self.assertGreater(db.execute(sql).fetchone()[0], budget)

    def test_colliding_ids_fail_before_touching_an_existing_output(self):
        self.path.write_bytes(b"previous catalogue")
        a = packaged("036000291452", 50, 12.5)
        b = packaged("0036000291452", 96, 24)
        with self.assertRaisesRegex(ValueError, "duplicate food IDs"):
            write_db(str(self.path), [a, b], SCHEMA)
        self.assertEqual(self.path.read_bytes(), b"previous catalogue")

    def test_exporter_uses_search_membership_and_keeps_its_tsv_format(self):
        a = packaged("5449000028921", 50, 12.5, popularity=10)
        b = packaged("42105220", 96, 24)
        db = self.open([a, b], {a.id})
        db.close()
        fixture = self.path.with_suffix(".fixture.tsv")
        fixture.write_text("drink\tUS\ten\tdrink\t\n")
        out = self.path.with_suffix(".tsv")
        details = self.path.with_suffix(".json")
        export(self.path, fixture, out, 30, details_path=details)
        self.assertEqual(out.read_text(), "drink\tUS\ten\tSame drink\n")
        import json

        self.assertEqual(json.loads(details.read_text())[0]["results"][0]["id"], a.id)


if __name__ == "__main__":
    unittest.main()
