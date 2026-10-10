#!/usr/bin/env python3
"""Export the current Android food-search results for the relevance evaluator.

This is a measurement tool, not production search code. GENERIC_SQL below is a
replica of FoodDao's GENERIC_SEARCH_HEAD + GENERIC_SEARCH_ORDER, BRANDED_SQL of
FoodDao.searchBranded, and the merge of FoodRepositoryImpl.search. Those two
constants are the single Kotlin-side definition, so a ranking change means
editing them and this file -- the only remaining copy of the ladder.

Usage:
    python tools/export_search_results.py \
        --db build/integration/after-fold.db \
        --fixture app/src/test/resources/search-relevance-fixtures.tsv \
        --out build/integration/search-results.tsv
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_nutrition_db import fold_name  # noqa: E402


NATIONAL_SOURCE = {
    "US": "usda", "FR": "ciqual", "DE": "bls", "ES": "bedca", "UK": "cofid",
    "NO": "matvaretabellen", "SE": "livsmedel", "FI": "fineli", "DK": "frida",
    "CH": "swiss", "PT": "tca", "CA": "cnf", "AU": "afcd",
    "NL": "nevo", "NZ": "foodfiles",
}
# Mirrors SettingsManager.LEAD_WORD_SOURCES: the primary shelves LEAD_WORD_MATCH applies to.
LEAD_WORD_SOURCES = frozenset({"nevo", "foodfiles"})

SEPARATOR_RE = re.compile(r"[ \t\n\r,;./\\|\-_\(\)\[\]\{\}\:\*\+\^\"'`!?&%#]+")


def sanitize(raw: str) -> str | None:
    tokens = [
        token[:64]
        for token in SEPARATOR_RE.split(fold_name(raw))
        if token and token.isalnum()
    ][:8]
    return " ".join(f'"{token}"*' for token in tokens) if tokens else None


def languages_for(language: str) -> list[str]:
    return list(dict.fromkeys((language, "en")))


# Mirrors TableLanguages.MIN_ALIAS_COVERAGE_PERCENT.
MIN_ALIAS_COVERAGE_PERCENT = 50

TABLE_LANGUAGES_SQL = """
SELECT lang FROM (
    SELECT f.lang AS lang, 2 AS own, COUNT(*) AS rows_covered
      FROM foods f
     WHERE f.source = :source AND f.lang IS NOT NULL AND f.lang <> ''
     GROUP BY f.lang
    UNION ALL
    SELECT fa.lang AS lang, 1 AS own, COUNT(DISTINCT fa.food_id) AS rows_covered
      FROM food_aliases fa
      JOIN foods f ON f.id = fa.food_id
     WHERE f.source = :source AND INSTR(fa.lang, '-') = 0
     GROUP BY fa.lang
)
WHERE rows_covered * 100 >= :min_coverage *
      (SELECT COUNT(*) FROM foods WHERE source = :source)
GROUP BY lang
ORDER BY MAX(own) DESC, MAX(rows_covered) DESC, lang
"""


def table_languages(db: sqlite3.Connection, source: str) -> list[str]:
    """Mirrors FoodDao.languagesServedBy. See TableLanguages.kt for what it answers and why."""
    if not source:
        return []
    return [
        row[0] for row in db.execute(
            TABLE_LANGUAGES_SQL,
            {"source": source, "min_coverage": MIN_ALIAS_COVERAGE_PERCENT},
        )
    ]


def sources_serving(
    db: sqlite3.Connection, languages: list[str], excluding: str,
) -> list[str]:
    """Mirrors TableLanguages.sourcesServing -- tables to borrow from."""
    wanted = set(languages)
    out = []
    for (source,) in db.execute(
        "SELECT DISTINCT source FROM foods WHERE source <> 'off' ORDER BY source"
    ):
        if source == excluding:
            continue
        if wanted.intersection(table_languages(db, source)):
            out.append(source)
    return out


def derived_languages(
    db: sqlite3.Connection, source: str, app_language: str,
) -> list[str]:
    """Mirrors SettingsManager.derivedFoodLanguages with no stored preference."""
    served = table_languages(db, source)
    if not served:
        return languages_for(app_language)
    return list(dict.fromkeys([x for x in served if x == app_language] + served))


# query-side rescue, mirroring FtsQuery.kt and CompoundLexicon.kt. Same standing
# hazard as the ladder below: this is the only other copy, and it must move in the same commit.
COMPOUND_LANGS = ("da", "nb", "sv", "de", "fi")
# Same 4 as the build side's COMPOUND_MIN_HEAD. 3 would let Danish `kød` be a head, but it
# changes nothing, because Danish
# fails on the STEM, not the head -- Frida never writes `svin` as a standalone word, so it is
# not in the Danish lexicon at any threshold. Keeping 4 keeps one number, not two.
QUERY_MIN_HEAD = 4
QUERY_MIN_STEM = 3
COMPOUND_LINKS = ("", "s", "es", "n", "en", "e", "er")
# `ter` is the Nordic umlaut plural once folded -- morötter -> morot, gulrøtter -> gulrot. It
# was added after measuring: without it both return nothing and a dish respectively, with it
# both return the carrot, and the 234-case sweep does not move. `or`/`ar` were tried too and
# bought nothing, so they are not here.
# Bare `n` is the commonest German feminine plural -- Linse -> Linsen -- and without it a
# Swiss reader's `linsen` reached nothing. Free: the 234-case sweep does not move.
PLURAL_SUFFIXES = ("s", "es", "t", "er", "en", "ter", "n")


def load_lexicon(path: Path) -> dict[str, tuple[str, set[str]]]:
    """{source: (lang, vocabulary)} from the `source<TAB>lang<TAB>word` asset.

    Keyed per source. The language comes along because compound splitting is gated
    on it and nothing else in the query path knows a source's language.
    """
    lexicon: dict[str, tuple[str, set[str]]] = {}
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3 or not parts[2]:
                continue
            source, lang, word = parts
            lexicon.setdefault(source, (lang, set()))[1].add(word)
    return lexicon


def split_compound(token: str, words: set[str]) -> tuple[str, str] | None:
    """(stem, head) for a compound whose halves are both real words, else None.

    The head is the SUFFIX -- Germanic and Finnish compounds put it last, so svine+kjott is a
    kjott. Longest head first, matching the build-side loop in split_compounds().
    """
    for cut in range(1, len(token) - QUERY_MIN_HEAD + 1):
        head, rest = token[cut:], token[:cut]
        if head not in words or head == token:
            continue
        for link in COMPOUND_LINKS:
            stem = rest[: -len(link)] if link and rest.endswith(link) else rest
            if len(stem) >= QUERY_MIN_STEM and stem in words:
                return stem, head
    return None


def depluralize(token: str, words: set[str]) -> str | None:
    """The singular of a plural query, but only when the singular is a word the data knows."""
    if token in words:
        return None
    for suffix in PLURAL_SUFFIXES:
        if not token.endswith(suffix):
            continue
        stem = token[: -len(suffix)]
        if len(stem) >= QUERY_MIN_STEM and stem in words:
            return stem
    return None


def rescue_variant(
    plain: str,
    source: str,
    lexicon: dict[str, tuple[str, set[str]]],
    split_first: bool = True,
) -> tuple[str, str] | None:
    """(plain, head) to retry with, or None. `head` is '' when nothing was split.

    Asks ONE source's vocabulary -- the national table whose shelf just failed to answer. The
    reader's whole language, the union of every table sharing it, both refused legitimate rescues (`lentils` is a word in USDA, so an Australian
    never got `lentil`) and fired on queries the reader's table was never going to answer
    (English `oats` against BEDCA rewrote the query for the FALLBACK shelf too, and turned
    correct results into `Pan de avena`). One table, one vocabulary avoids both.
    """
    if not plain or not plain.isalnum():
        return None
    entry = lexicon.get(source)
    if entry is None:
        return None
    lang, words = entry

    def try_split() -> tuple[str, str] | None:
        if lang not in COMPOUND_LANGS:
            return None
        return split_compound(plain, words)

    def try_plural() -> tuple[str, str] | None:
        stem = depluralize(plain, words)
        return (stem, "") if stem else None

    order = (try_split, try_plural) if split_first else (try_plural, try_split)
    for attempt in order:
        if found := attempt():
            return found
    return None


# Mirrors FoodDao.HEAD_MATCH_TIER. The "has the reader's own table actually answered" test:
# a name or alias match, not a search_extra one. Named here because two call sites in this file
# have to agree on it.
HEAD_MATCH_TIER = 2


def language_priority(languages: list[str]) -> str:
    return "," + ",".join(languages) + ","


def strip_like_wildcards(text: str) -> str:
    """Mirrors `stripLikeWildcards` in FtsQuery.kt, and must agree with it exactly.

    The tier ladder below compares `:plain` against about twenty LIKE patterns, none of which
    carries an ESCAPE clause. `fold_name` normalises and lowercases but does not touch `%` or `_`,
    so a query containing either would mean something different to the ladder than to the FTS
    MATCH in front of it, which keeps only letters and digits.

    Discarded rather than escaped, matching `sanitize`'s existing decision on the other half of the
    same comparison. The custom-food shelf escapes instead, and that is not an inconsistency: it
    has no FTS gate in front of it, so it is the only comparison that sees the query and has to
    honour it literally. See `escapeForLike`.
    """
    return text.replace("%", "").replace("_", "")


TIER = """
CASE
    WHEN {column} = :plain OR {column} = :plain || 's' THEN 0
    WHEN {column} LIKE :plain || ',%' OR {column} LIKE :plain || 's,%' THEN 1
    WHEN {column} LIKE :plain || ' %' OR {column} LIKE :plain || 's %' THEN {head_tier}
    WHEN ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE({column}, ',', ' '), '-', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
         LIKE '% ' || :plain || ' %' THEN 3
    WHEN ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE({column}, ',', ' '), '-', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
         LIKE '% ' || :plain || '%' THEN 4
    ELSE 5
END
"""
# `head_tier` is the one place the two ladders differ, so it is an argument rather than a string
# edit applied afterwards: a missing key raises KeyError, while a missed substring replacement
# would silently produce a plausible wrong ladder.
NAME_TIER = TIER.format(column="f.name_folded", head_tier=2)
# an alias's "starts with the query, then a space" match (tier 2 in TIER) does not mean
# what it means for a native name. A native "Butter, salted"/"Butter gesalzen" genuinely names the
# food with a qualifier -- but an English ALIAS gloss of a foreign compound dish uses the same
# head-initial shape for completely different foods: "Butter rusk" (Butterzwieback), "Butter
# potatoes" (Butterkartoffeln), "Apple soup" (Äppelsoppa), "Orange soft drink" (Orangenlimonade).
# These are legitimate, correct translations -- not a data bug -- but they let an unrelated dish
# tie a genuine qualifier match at the same tier, and word count then picks the shortest one,
# which is how "Joghurtbutter" ends up beating "Butter gesalzen" for a bare "butter" query.
# Demoted to tier 3 (same as "the word appears anywhere once flattened"), which is what the
# evidence actually supports. Tiers 0 (exact) and 1 (comma-qualified) are untouched.
ALIAS_TIER = TIER.format(column="f.alias_names", head_tier=3)
# authorised follow-up: a compound-head match scores tier 3, the tier that already
# means "the whole word appears once separators are flattened". Never lower -- a compound must
# not outrank the plain food. Kept in step with GENERIC_SEARCH_ORDER in FoodDao.kt.
EXTRA_TIER = """
CASE
    WHEN f.search_extra IS NULL THEN 5
    WHEN f.search_extra = :plain
      OR f.search_extra LIKE :plain || ' %'
      OR f.search_extra LIKE '% ' || :plain || ' %'
      OR f.search_extra LIKE '% ' || :plain THEN 3
    ELSE 5
END
"""
# mirrors FoodDao.READER_ALIAS_TIER. Scores the ONE alias in the reader's own
# language(s) against tiers 0-1 only, never 2: an alias gloss of a foreign compound ("Butter rusk"
# for Butterzwieback) has the shape of a genuine qualifier and would win a bare "butter". `{language_placeholders}` is
# filled in at the call site, same as DISPLAY, because it depends on the reader's language list.
READER_ALIAS_TIER = """
CASE
    WHEN (SELECT fa.name FROM food_aliases fa
          WHERE fa.food_id = f.id AND fa.lang IN ({language_placeholders})
          ORDER BY INSTR(:priority, ',' || fa.lang || ',') LIMIT 1)
         IN (:plain, :plain || 's') THEN 0
    WHEN (SELECT fa.name FROM food_aliases fa
          WHERE fa.food_id = f.id AND fa.lang IN ({language_placeholders})
          ORDER BY INSTR(:priority, ',' || fa.lang || ',') LIMIT 1)
         LIKE :plain || ',%'
      OR (SELECT fa.name FROM food_aliases fa
          WHERE fa.food_id = f.id AND fa.lang IN ({language_placeholders})
          ORDER BY INSTR(:priority, ',' || fa.lang || ',') LIMIT 1)
         LIKE :plain || 's,%' THEN 1
    ELSE 5
END
"""
# mirrors FoodDao.LEAD_WORD_MATCH -- the name's first word (or it less a trailing `s` or
# `en`) is one of the query's words, on the primary shelf of a LEAD_WORD_SOURCES table, for a
# multiword query. See the Kotlin comment for why and what it was measured to do.
_LEAD_WORD_HEAD = (
    "SUBSTR(REPLACE(f.name_folded, ',', ' '), 1, "
    "INSTR(REPLACE(f.name_folded, ',', ' ') || ' ', ' ') - 1)"
)
LEAD_WORD_MATCH = (
    "(:lead_word_source <> '' AND f.source = :lead_word_source"
    " AND INSTR(:plain, ' ') > 0"
    f" AND (INSTR(' ' || :plain || ' ', ' ' || {_LEAD_WORD_HEAD} || ' ') > 0"
    f" OR ({_LEAD_WORD_HEAD} LIKE '%s' AND INSTR(' ' || :plain || ' ',"
    f" ' ' || SUBSTR({_LEAD_WORD_HEAD}, 1, LENGTH({_LEAD_WORD_HEAD}) - 1) || ' ') > 0)"
    f" OR ({_LEAD_WORD_HEAD} LIKE '%en' AND INSTR(' ' || :plain || ' ',"
    f" ' ' || SUBSTR({_LEAD_WORD_HEAD}, 1, LENGTH({_LEAD_WORD_HEAD}) - 2) || ' ') > 0)))"
)
# Mirrors FoodDao.LEAD_WORD_ARM.
LEAD_WORD_ARM = f"""
CASE
    WHEN {LEAD_WORD_MATCH}
     AND ' ' || REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(f.name_folded, ',', ' '), '-', ' '), '(', ' '), ')', ' '), '  ', ' '), '  ', ' '), '  ', ' ') || ' '
         LIKE '% ' || :plain || ' %' THEN 0
    WHEN {LEAD_WORD_MATCH} THEN 1
    ELSE 2
END
"""
GENERIC_TIER = (
    f"MIN({NAME_TIER}, {ALIAS_TIER}, {EXTRA_TIER}, {{reader_alias_tier}}, "
    f"CASE WHEN {LEAD_WORD_MATCH} THEN 3 ELSE 5 END)"
)

# Below the tier ladder: above it, a row that merely contains the compound's head word jumps
# ahead of a row whose name starts with the modifier, and `svinekjøtt` returns Villsvin (wild
# boar) at #1.
#
# The `:head = ''` branch is required. Without it the empty head reads as LIKE '%  %' (two
# spaces), and every name carrying a comma matches, because the flattening turns ", " into
# exactly that. That would silently reorder every unsplit query in the app.
HEAD_ARM = """
CASE
    WHEN :head = '' THEN 0
    WHEN ' ' || REPLACE(REPLACE(f.name_folded, ',', ' '), '-', ' ') || ' '
         LIKE '% ' || :head || ' %' THEN 0
    ELSE 1
END
"""

# Mirrors FoodDao.DISPLAY_NAME. The f.lang clause is what makes native names work:
# foods.name is itself in a language, so an alias may only win if the
# reader ranks its language above the row's own. Without it a French reader on a
# French row is shown the English alias, because languages_for always appends 'en'.
DISPLAY = """
COALESCE((
    SELECT fa.name FROM food_aliases fa
    WHERE fa.food_id = f.id AND fa.lang IN ({language_placeholders})
      AND (f.lang IS NULL
           OR INSTR(:priority, ',' || f.lang || ',') = 0
           OR INSTR(:priority, ',' || fa.lang || ',')
              < INSTR(:priority, ',' || f.lang || ','))
    ORDER BY INSTR(:priority, ',' || fa.lang || ',')
    LIMIT 1
), f.name) AS display_name
"""

GENERIC_SQL = """
SELECT f.id, f.name, f.source, {display}, {generic_tier} AS match_tier
FROM food_search s JOIN foods f ON f.id = s.rowid
WHERE food_search MATCH :fts
  AND f.source <> 'off'
  AND {source_predicate}
ORDER BY
    CASE
        WHEN f.lang IN ({language_placeholders}) AND f.lang <> 'en'
         AND (f.name_folded = :plain OR f.name_folded = :plain || 's'
           OR f.name_folded LIKE :plain || ',%' OR f.name_folded LIKE :plain || 's,%'
           OR f.name_folded LIKE :plain || ' %' OR f.name_folded LIKE :plain || 's %'
           OR f.alias_names = :plain OR f.alias_names = :plain || 's'
           OR f.alias_names LIKE :plain || ',%' OR f.alias_names LIKE :plain || 's,%'
           OR f.alias_names LIKE :plain || ' %' OR f.alias_names LIKE :plain || 's %') THEN 0
        ELSE 1
    END,
    {generic_tier},
    {lead_word_arm},
    {representative_order},
    {head_arm},
    f.basic_rank,
    LENGTH(f.name) - LENGTH(REPLACE(f.name, ' ', '')),
    LENGTH(f.name),
    bm25(food_search, 5.0, 1.0, 3.0, 0.0),
    f.quality_score DESC
LIMIT :limit
"""

BRANDED_SQL = """
SELECT f.id, f.name, f.source, f.name AS display_name
FROM food_search s JOIN foods f ON f.id = s.rowid
WHERE food_search MATCH :fts AND f.source = 'off'
ORDER BY
    CASE WHEN f.name_folded = :plain THEN 0
         WHEN f.name_folded LIKE :plain || '%' THEN 1 ELSE 2 END,
    bm25(food_search, 5.0, 1.0, 3.0, 0.0),
    f.quality_score DESC
LIMIT :limit
"""


def load_cases(path: Path) -> list[tuple[str, str, str]]:
    cases = []
    with path.open(encoding="utf-8", newline="") as fh:
        for number, row in enumerate(csv.reader(fh, delimiter="\t"), 1):
            if not row or row[0].startswith("#"):
                continue
            if len(row) == 4:
                # The staple fixture omits the trailing cell when there is nothing to reject.
                row = row + [""]
            if len(row) != 5:
                raise ValueError(f"{path}:{number}: expected 5 columns, got {len(row)}")
            cases.append((row[0], row[1], row[2]))
    if not cases:
        raise ValueError(f"{path}: no cases")
    return cases


def query(db: sqlite3.Connection, sql: str, params: dict[str, object]) -> list[sqlite3.Row]:
    return db.execute(sql, params).fetchall()


def representative_order(db: sqlite3.Connection) -> str:
    """The shared preference term; old measurement databases have no preference metadata."""
    return (
        "COALESCE((SELECT 1 - preferred FROM food_search_members WHERE food_id=f.id), 0)"
        if any(row[1] == "preferred" for row in db.execute("PRAGMA table_info(food_search_members)"))
        else "CAST(0 AS INTEGER)"
    )


def export(
    db_path: Path,
    fixture_path: Path,
    output_path: Path,
    limit: int,
    lexicon_path: Path | None = None,
    split_first: bool = True,
    trigger: str = "headmatch",
    details_path: Path | None = None,
) -> int:
    if not db_path.is_file():
        raise FileNotFoundError(f"database not found: {db_path}")
    if limit < 1:
        raise ValueError("--limit must be positive")

    db = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    required = {"foods", "food_search", "food_aliases"}
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    missing = required - tables
    if missing:
        raise ValueError(f"database is missing required tables: {', '.join(sorted(missing))}")

    lexicon = load_lexicon(lexicon_path) if lexicon_path else {}
    # Older measured databases have membership without this presentation preference.
    preference = representative_order(db)

    # The TSV stays compatible with the evaluator and existing consumers. Optional identity
    # details distinguish same-name records when comparing catalogue/search-membership changes.
    details = []
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh, delimiter="\t", lineterminator="\n")
        for raw_query, country, language in load_cases(fixture_path):
            fts = sanitize(raw_query)
            if fts is None:
                continue
            source = NATIONAL_SOURCE.get(country, "")
            # one database, one language. The food-name languages are derived from the
            # table the reader chose rather than from the app language plus a blanket `en` --
            # unconditionally, matching FoodRepositoryImpl.foodLanguages, which never looked at
            # the (now-removed) single-source setting either.
            languages = derived_languages(db, source, language)
            language_placeholders = ", ".join(f":lang_{i}" for i in range(len(languages)))
            params = {
                "fts": fts,
                "plain": strip_like_wildcards(fold_name(raw_query.strip())),
                "priority": language_priority(languages),
                "head": "",
                "lead_word_source": source if source in LEAD_WORD_SOURCES else "",
                "limit": limit,
            }
            params.update({f"lang_{i}": value for i, value in enumerate(languages)})

            def generic(predicate: str, params: dict[str, object]) -> list[sqlite3.Row]:
                return query(db, GENERIC_SQL.format(
                    display=DISPLAY.format(language_placeholders=language_placeholders),
                    source_predicate=predicate,
                    language_placeholders=language_placeholders,
                    generic_tier=GENERIC_TIER.format(
                        reader_alias_tier=READER_ALIAS_TIER.format(
                            language_placeholders=language_placeholders,
                        ),
                    ),
                    head_arm=HEAD_ARM,
                    lead_word_arm=LEAD_WORD_ARM,
                    representative_order=preference,
                ), params)

            primary_predicate = "f.source = :national_source" if source else "1 = 1"
            primary = generic(primary_predicate, {**params, "national_source": source})

            # The rescue fires when the reader's own shelf produced no HEAD match --
            # not when it produced no rows. `storfekjøtt` does return something (beef fat, whose
            # name contains the whole compound at tier 3) and would never be rescued on a
            # zero-rows test. This is the same "has the reader's table actually answered" test
            # that the merge and the fallback below use.
            answered = (
                len(primary) > 0 if trigger == "empty"
                else any(row["match_tier"] <= HEAD_MATCH_TIER for row in primary)
            )
            if lexicon and not answered:
                variant = rescue_variant(params["plain"], source, lexicon, split_first)
                if variant:
                    plain, head = variant
                    retry = sanitize(plain)
                    if retry:
                        params = {**params, "fts": retry, "plain": plain, "head": head}
                        primary = generic(primary_predicate, {**params, "national_source": source})

            # Borrow from tables in the reader's language when their own table has no row.
            if source:
                # Zero rows, and the reader's PRIMARY language only. See FoodRepositoryImpl.
                lenders = (
                    sources_serving(db, languages[:1], source) if not primary else []
                )
                fallback = generic(
                    "INSTR(:source_list, ',' || f.source || ',') > 0",
                    {**params, "national_source": source, "lead_word_source": "",
                     "source_list": "," + ",".join(lenders) + ","},
                ) if lenders else []
            else:
                fallback = []
            generic_ids = {row["id"] for row in primary + fallback}
            branded = [] if len(generic_ids) >= limit else query(
                db, BRANDED_SQL, {**params, "limit": limit - len(generic_ids)}
            )
            # Mirrors composeSearchResults: head matches (tier <= 2) first with the national
            # source ahead of the fallback, then everything else on the same rule, and branded
            # appended last. Merging on the raw tier instead was measured and rejected -- the tier
            # encodes each table's punctuation habits, so it handed Swedish readers Norwegian food
            # on a comma. Python's sort is stable, as Kotlin's sortedWith is, so inside one bucket
            # and one shelf the SQL ordering still decides.
            merged = [
                row for row, _ in sorted(
                    [(row, 0) for row in primary] + [(row, 1) for row in fallback],
                    key=lambda pair: (
                        0 if pair[0]["match_tier"] <= 2 else 1, pair[1], pair[0]["match_tier"]
                    ),
                )
            ]
            rows = []
            seen: set[int] = set()
            for row in merged + branded:
                if row["id"] in seen:
                    continue
                seen.add(row["id"])
                rows.append(row)
                if len(rows) == limit:
                    break
            for row in rows:
                writer.writerow((raw_query, country, language, row["display_name"]))
            if details_path is not None:
                details.append({
                    "query": raw_query, "country": country, "language": language,
                    "results": [{k: row[k] for k in ("id", "source", "name", "display_name")}
                                for row in rows],
                })
    db.close()
    if details_path is not None:
        details_path.parent.mkdir(parents=True, exist_ok=True)
        details_path.write_text(json.dumps(details, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, default=ROOT / "app/src/test/resources/search-relevance-fixtures.tsv")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--details", type=Path,
                        help="Optional JSON of food IDs and sources; leaves the evaluator TSV unchanged.")
    parser.add_argument("--limit", type=int, default=30)
    parser.add_argument("--lexicon", type=Path,
                        default=ROOT / "app/src/main/assets/compound-lexicon.txt",
                        help="The query-side compound lexicon.")
    parser.add_argument("--no-rescue", action="store_true",
                        help="Measure the search without the compound/plural rescue, which is how the "
                             "before/after is taken.")
    parser.add_argument("--trigger", choices=("headmatch", "empty"), default="headmatch",
                        help="When the rescue fires: no head match on the reader's shelf "
                             "(default), or no rows at all.")
    parser.add_argument("--plural-first", action="store_true",
                        help="Try de-pluralisation before compound splitting")
    args = parser.parse_args()
    lexicon = None if args.no_rescue else args.lexicon
    return export(args.db, args.fixture, args.out, args.limit, lexicon,
                  not args.plural_first, args.trigger, args.details)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, sqlite3.Error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)
