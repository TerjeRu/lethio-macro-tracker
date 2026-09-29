#!/usr/bin/env python3
"""Check nutrition database integrity and quality. Usage: python tools/audit_nutrition_db.py DATABASE"""

from __future__ import annotations

import re
import sqlite3
import sys
import unicodedata

GENERIC_SOURCES = frozenset({"usda", "ciqual", "bls", "bedca", "cofid",
                             "matvaretabellen", "livsmedel", "fineli", "frida",
                             "swiss", "tca", "cnf", "afcd"})
BRANDED_SOURCES = frozenset({"off"})
ALL_SOURCES = GENERIC_SOURCES | BRANDED_SOURCES

def _sql_list(sources) -> str:

    return "(" + ",".join(f"'{s}'" for s in sorted(sources)) + ")"

CARB_CONVENTIONS = frozenset({"by_difference", "available", "available_monosaccharide"})

CARB_CONVENTION_BY_SOURCE = {
    "usda": "by_difference",
    "cnf": "by_difference",
    "cofid": "available_monosaccharide",
    "ciqual": "available",
    "bls": "available",
    "bedca": "available",
    "matvaretabellen": "available",
    "livsmedel": "available",
    "fineli": "available",
    "frida": "available",
    "swiss": "available",
    "tca": "available",
    "afcd": "available",
    "off": "available",
}

NUTRIENT_COLUMNS = (
    "kcal_100g", "protein_100g", "fat_100g", "carbs_100g",
    "fiber_100g", "sugar_100g", "sat_fat_100g", "sodium_100g",
)

def _empty_column_sql() -> str:

    exempt = {}
    parts = []
    for source in sorted(GENERIC_SOURCES):
        for column in NUTRIENT_COLUMNS:
            if (source, column) in exempt:
                continue
            parts.append(
                f"SELECT '{source}' s, '{column}' c FROM foods WHERE source = '{source}'"
                f" GROUP BY source HAVING MAX({column} IS NOT NULL) = 0"
            )
    return "SELECT COUNT(*) FROM (" + " UNION ALL ".join(parts) + ")"

CHECKS: dict[str, tuple[str, int, str]] = {
    "servings with non-positive grams": (
        "SELECT COUNT(*) FROM food_servings WHERE grams <= 0", 0,
        "always a defect"),
    "servings outside 1g-2000g": (
        "SELECT COUNT(*) FROM food_servings WHERE grams < 1 OR grams > 2000", 0,
        "OFF reports servings of 0.000001 g; USDA lists whole carcasses at 5.7 kg"),
    "duplicate serving label within one food": (
        "SELECT COUNT(*) FROM (SELECT food_id, LOWER(TRIM(label)) l FROM food_servings"
        " GROUP BY food_id, l HAVING COUNT(*) > 1)", 0,
        "renders as two identical chips in the unit picker"),
    "serving label that is a unit of measure": (
        "SELECT COUNT(*) FROM food_servings WHERE LOWER(TRIM(label)) IN"
        " ('g','oz','ml','l','kg','lb','gram','grams','ounce')", 0,
        "USDA lists 'oz' as a portion; it collides with the built-in ounces unit. Note 'serving'"
        " is NOT listed here -- it is a canonical token the app translates, not junk"),
    "names containing an HTML entity": (
        "SELECT COUNT(*) FROM foods WHERE name LIKE '%&amp;%' OR name LIKE '%&#%'"
        " OR name LIKE '%&quot;%' OR name LIKE '%&nbsp;%'", 50,
        "scraper residue; a long tail of rare entities remains -- 13 of 872,768 rows"
        " (0.15 per 10k, all off) in one measured export and 0 of 68,631 in another."
        " The tail scales with the branded population, and 50 stays ~4x"
        " the measured tail: a scraper-wide encoding break would produce thousands"
        " of rows, which 50 still catches"),
    "names containing a URL": (
        "SELECT COUNT(*) FROM foods WHERE name LIKE '%http%' OR name LIKE '%www.%'", 0,
        "always spam"),
    "name identical to brand": (
        "SELECT COUNT(*) FROM foods WHERE brand IS NOT NULL"
        " AND LOWER(TRIM(name)) = LOWER(TRIM(brand))", 0,
        "renders as 'Pepsi · Pepsi'"),
    "duplicate search rows within a source (name, brand, energy)": (
        "SELECT COUNT(*) FROM (SELECT LOWER(name) n, LOWER(COALESCE(brand,'')) b,"
        " CAST(kcal_100g / 50 AS INT) e FROM foods"
        " WHERE id IN (SELECT rowid FROM food_search)"
        " GROUP BY source, n, b, e HAVING COUNT(*) > 1)", 0,
        "typed search is grouped within each source; distinct national records keep their identity"),
    "zero energy alongside non-zero macros": (
        "SELECT COUNT(*) FROM foods WHERE kcal_100g = 0"
        " AND (protein_100g > 0.5 OR fat_100g > 0.5 OR carbs_100g > 0.5)", 200,
        "label rounding on near-zero drinks is legitimate"),
    "sodium above 45 g/100g": (
        "SELECT COUNT(*) FROM foods WHERE sodium_100g > 45", 0,
        "pure salt is ~39 g; anything higher is a mg/g unit error"),
    "sugar exceeding carbohydrate": (
        "SELECT COUNT(*) FROM foods WHERE sugar_100g > carbs_100g + 1", 0,
        "a component cannot exceed its total"),
    "saturated fat exceeding total fat": (
        "SELECT COUNT(*) FROM foods WHERE sat_fat_100g > fat_100g + 1", 0,
        "a component cannot exceed its total"),
    "generic source with a nutrient column that is empty for every row": (
        _empty_column_sql(), 0,
        "a loader that never mapped a field the source publishes -- silent, and"
        " invisible to every per-row check. Frida was built without its saturated"
        " fat this way; see _empty_column_sql"),
    "energy outside 0-900 kcal/100g": (
        "SELECT COUNT(*) FROM foods WHERE kcal_100g < 0 OR kcal_100g > 900", 0,
        "pure fat is ~900; higher means kJ recorded as kcal"),
    "unexpected source value": (
        "SELECT COUNT(*) FROM foods WHERE source NOT IN " + _sql_list(ALL_SOURCES), 0,
        "provenance drives attribution and the trust indicator"),
    "unexpected basic_rank value": (
        "SELECT COUNT(*) FROM foods WHERE basic_rank NOT IN (0,1,2)", 0,
        "drives search ranking"),
    "unexpected carbs_convention value": (
        "SELECT COUNT(*) FROM foods WHERE carbs_convention NOT IN "
        + _sql_list(CARB_CONVENTIONS), 0,
        "the display layer converts on these three values by name; a fourth"
        " would pass through unconverted and mislabelled"),
    "carbs_convention disagrees with source": (
        "SELECT COUNT(*) FROM foods WHERE " + " OR ".join(
            f"(source = '{source}' AND carbs_convention <> '{convention}')"
            for source, convention in sorted(CARB_CONVENTION_BY_SOURCE.items())
        ), 0,
        "each source's convention follows its publisher register and file-side evidence;"
        " loader changes must preserve it"),
    "orphaned servings": (
        "SELECT COUNT(*) FROM food_servings s"
        " LEFT JOIN foods f ON f.id = s.food_id WHERE f.id IS NULL", 0,
        "always a defect"),
    "unexpected source_rank value": (
        "SELECT COUNT(*) FROM foods WHERE source_rank NOT IN (0,1)", 0,
        "first term of the search ordering"),
    "source_rank disagrees with source": (
        "SELECT COUNT(*) FROM foods WHERE"
        f" (source IN {_sql_list(GENERIC_SOURCES)} AND source_rank <> 0)"
        f" OR (source IN {_sql_list(BRANDED_SOURCES)} AND source_rank <> 1)", 0,
        "a new generic source left at the branded default ranks below packaged products"),
    "generic food with no language": (
        "SELECT COUNT(*) FROM foods WHERE source NOT IN " + _sql_list(BRANDED_SOURCES) + " AND lang IS NULL", 0,
        "generic names are the ones search ranks by language"),
    "branded food claiming a language": (
        "SELECT COUNT(*) FROM foods WHERE source IN " + _sql_list(BRANDED_SOURCES) + " AND lang IS NOT NULL", 0,
        "Open Food Facts carries no language column, so any value here is a guess"),
    "empty name_folded": (
        "SELECT COUNT(*) FROM foods WHERE name_folded IS NULL OR name_folded = ''", 0,
        "an unfolded row can never satisfy the LIKE ranking tiers"),
    "name_folded still holds uppercase ASCII": (
        "SELECT COUNT(*) FROM foods WHERE name_folded GLOB '*[A-Z]*'", 0,
        "folding is what the ranking compares against; unfolded rows sink silently"),
    "orphaned aliases": (
        "SELECT COUNT(*) FROM food_aliases a"
        " LEFT JOIN foods f ON f.id = a.food_id WHERE f.id IS NULL", 0,
        "always a defect"),
    "alias not reachable from its food": (
        "SELECT COUNT(*) FROM food_aliases a JOIN foods f ON f.id = a.food_id"
        " WHERE f.alias_names IS NULL", 0,
        "alias_names is what the FTS index reads; a row missing it is unsearchable by alias"),
    "alias identical to its food's name": (
        "SELECT COUNT(*) FROM food_aliases a JOIN foods f ON f.id = a.food_id"
        " WHERE a.name = f.name", 0,
        "the Ciqual loader skips a French name that reads the same as the English one;"
        " storing it only pads the table and the FTS index"),
    "duplicate alias (food, lang, name)": (
        "SELECT COUNT(*) FROM (SELECT food_id, lang, name FROM food_aliases"
        " GROUP BY food_id, lang, name HAVING COUNT(*) > 1)", 0,
        "the same name twice for one food in one language is a pipeline bug, not data"),
    "alias lang not well-formed": (
        "SELECT COUNT(*) FROM food_aliases WHERE lang NOT GLOB '[a-z][a-z]'"
        " AND lang NOT GLOB '[a-z][a-z]-[A-Z][A-Z]'", 0,
        "a typo'd lang silently never matches a user's locale"),
    "orphaned barcodes": (
        "SELECT COUNT(*) FROM food_barcodes b"
        " LEFT JOIN foods f ON f.id = b.food_id WHERE f.id IS NULL", 0,
        "a code that resolves to nothing; the scanner would report a hit and open an empty food"),
    "food whose own barcode is not scannable": (
        "SELECT COUNT(*) FROM foods f WHERE f.barcode IS NOT NULL AND NOT EXISTS"
        " (SELECT 1 FROM food_barcodes b WHERE b.barcode = f.barcode AND b.food_id = f.id)", 0,
        "food_barcodes is the scanner's lookup table; a food missing from it can be"
        " found by typing but not by scanning"),
    "barcode resolves to a different package's record": (
        "SELECT COUNT(*) FROM food_barcodes b JOIN foods f ON f.id = b.food_id"
        " WHERE f.barcode IS NULL OR b.barcode <> f.barcode", 0,
        "similar display names do not establish identical nutrition or servings"),
    "barcode that is not digits, 8-14 of them": (
        "SELECT COUNT(*) FROM food_barcodes WHERE barcode GLOB '*[^0-9]*'"
        " OR LENGTH(barcode) < 8 OR LENGTH(barcode) > 14", 0,
        "clean_barcode() enforces this on the way in; a violation here means a code reached the"
        " table around it, and no scanner can ever emit one"),
    "alias_names without alias rows": (
        "SELECT COUNT(*) FROM foods f WHERE f.alias_names IS NOT NULL"
        " AND NOT EXISTS (SELECT 1 FROM food_aliases a WHERE a.food_id = f.id)", 0,
        "alias_names is what the FTS index reads; a value with no rows behind it was invented"),
}

SANITY_WHOLE_FOODS = (
    "banana", "milk", "egg", "bread", "apple", "potato", "tomato", "carrot",
    "salmon", "butter", "orange", "rice", "chicken breast",
)

SANITY_DERIVATIVES = ("olive oil", "flour", "orange juice", "peanut butter")

RANKING_SQL = """
SELECT f.name, f.source, f.kcal_100g, f.basic_rank, f.alias_names
FROM food_search s JOIN foods f ON f.id = s.rowid
WHERE food_search MATCH :fts
ORDER BY
  CASE WHEN f.source IN {generic} THEN 0 ELSE 1 END,
  MIN(
    CASE WHEN f.name_folded = :q OR f.name_folded = :q || 's' THEN 0
         WHEN f.name_folded LIKE :q || ',%' OR f.name_folded LIKE :q || 's,%' THEN 1
         WHEN f.name_folded LIKE :q || ' %'  OR f.name_folded LIKE :q || 's %' THEN 2
         WHEN ' ' || REPLACE(REPLACE(f.name_folded,',',' '),'-',' ') || ' '
              LIKE '% ' || :q || ' %' THEN 3
         WHEN ' ' || REPLACE(REPLACE(f.name_folded,',',' '),'-',' ') || ' '
              LIKE '% ' || :q || '%' THEN 4
         ELSE 5 END,
    -- Prefix matches on the concatenated alias bag are capped at tier 3: a gloss of a
    -- compound can look like a head noun without being an exact match in the reader's language.
    CASE WHEN f.alias_names = :q OR f.alias_names = :q || 's' THEN 0
         WHEN f.alias_names LIKE :q || ',%' OR f.alias_names LIKE :q || 's,%' THEN 1
         WHEN f.alias_names LIKE :q || ' %' OR f.alias_names LIKE :q || 's %' THEN 3
         WHEN ' ' || REPLACE(REPLACE(f.alias_names,',',' '),'-',' ') || ' '
              LIKE '% ' || :q || ' %' THEN 3
         WHEN ' ' || REPLACE(REPLACE(f.alias_names,',',' '),'-',' ') || ' '
              LIKE '% ' || :q || '%' THEN 4
         ELSE 5 END,
    CASE WHEN f.search_extra IS NULL THEN 5
         WHEN f.search_extra = :q
           OR f.search_extra LIKE :q || ' %'
           OR f.search_extra LIKE '% ' || :q || ' %'
           OR f.search_extra LIKE '% ' || :q THEN 3
         ELSE 5 END
  ),
  {representative_order},
  f.basic_rank,
  LENGTH(f.name) - LENGTH(REPLACE(f.name,' ','')),
  LENGTH(f.name),
  bm25(food_search, 5.0, 1.0, 3.0, 0.0),
  f.quality_score DESC
LIMIT 1
"""

RANKING_SQL = RANKING_SQL.format(generic=_sql_list(GENERIC_SOURCES), representative_order="{representative_order}")

def representative_order(db: sqlite3.Connection) -> str:
    return (
        "COALESCE((SELECT 1 - preferred FROM food_search_members WHERE food_id=f.id), 0)"
        if any(row[1] == "preferred" for row in db.execute("PRAGMA table_info(food_search_members)"))
        else "CAST(0 AS INTEGER)"
    )

_MOJIBAKE = re.compile(r"[ÃÂ][\x80-\xbf]|â€|Ã©|Ã¨|Ã¤")

def audit(path: str, verbose: bool = True) -> bool:
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    ok = True

    total = db.execute("SELECT COUNT(*) FROM foods").fetchone()[0]
    servings = db.execute("SELECT COUNT(*) FROM food_servings").fetchone()[0]
    if verbose:
        print(f"\n  audit: {path} — {total:,} foods, {servings:,} servings")

    for label, (sql, budget, why) in CHECKS.items():
        n = db.execute(sql).fetchone()[0]
        passed = n <= budget
        ok &= passed
        if verbose and (not passed or n):
            mark = "ok  " if passed else "FAIL"
            print(f"      [{mark}] {n:>7,} / budget {budget:<6} {label}")
            if not passed:
                print(f"               ({why})")

    rows = db.execute("SELECT name FROM foods").fetchall()
    mojibake = sum(1 for r in rows if _MOJIBAKE.search(r["name"] or ""))

    letterless = sum(1 for r in rows if not any(c.isalpha() for c in (r["name"] or "")))
    controls = sum(1 for r in rows
                   if any(unicodedata.category(c) == "Cc" for c in (r["name"] or "")))
    for label, n, budget in (("names with mojibake", mojibake, 0),
                             ("names with control characters", controls, 0),
                             ("names with no letters in any script", letterless, 0)):
        passed = n <= budget
        ok &= passed
        if verbose and (not passed or n):
            print(f"      [{'ok  ' if passed else 'FAIL'}] {n:>7,} / budget {budget:<6} {label}")

    from build_nutrition_db import fold_name

    unfolded = 0
    cur_id = None
    cur_names = []
    cur_stored = ""
    alias_rows = db.execute(
        "SELECT f.id, f.alias_names, a.name FROM foods f"
        " JOIN food_aliases a ON a.food_id = f.id"

        " WHERE f.alias_names IS NOT NULL"
        " ORDER BY f.id, a.id"
    ).fetchall()
    for row in alias_rows:
        if row["id"] != cur_id:
            if cur_id is not None and cur_stored != " ".join(fold_name(n) for n in cur_names):
                unfolded += 1
            cur_id, cur_names, cur_stored = row["id"], [], row["alias_names"]
        cur_names.append(row["name"])
    if cur_id is not None and cur_stored != " ".join(fold_name(n) for n in cur_names):
        unfolded += 1
    passed = unfolded == 0
    ok &= passed
    if verbose and (not passed or unfolded):
        print(f"      [{'ok  ' if passed else 'FAIL'}] {unfolded:>7,} / budget 0      "
              f"alias_names not folded")

    if verbose:
        print("      ranking sanity:")
    for term in SANITY_WHOLE_FOODS + SANITY_DERIVATIVES:
        fts = " ".join(f'"{t}"*' for t in term.split())
        row = db.execute(RANKING_SQL.format(representative_order=representative_order(db)),
                         {"fts": fts, "q": term}).fetchone()
        expect_whole = term in SANITY_WHOLE_FOODS
        head = term.split()[0]
        good = (
            row is not None
            and (head in row["name"].lower() or head in (row["alias_names"] or "").lower())
            and (row["basic_rank"] < 2 or not expect_whole)
        )
        ok &= good
        if verbose:
            name = row["name"][:44] if row else "NO RESULTS"
            print(f"         [{'ok  ' if good else 'FAIL'}] {term:<15} -> {name}")

    db.close()
    return ok

def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    return 0 if audit(sys.argv[1]) else 1

if __name__ == "__main__":
    sys.exit(main())
