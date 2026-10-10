#!/usr/bin/env python3
"""Build the bundled nutrition database from Open Food Facts, USDA FDC, ANSES-Ciqual,
the German BLS, Spanish BEDCA, UK CoFID, the Nordic four (Matvaretabellen,
Livsmedelsdatabasen, Fineli, Frida), the Swiss FCDB, the Portuguese TCA, the
Canadian Nutrient File and the Australian AFCD.

Standard library only. Verified against Python 3.14, which bundles SQLite 3.50 with FTS5
including external-content `rebuild`. No pandas, no shell heredocs, runs natively on Windows.

Two outputs from one pass:

  nutrition.db   the full catalogue
  seed.db        a catalogue (optionally size-limited) bundled in the APK

Sources take LOCAL PATHS. This script never downloads anything; the README lists where each
publisher's data comes from.

Usage:
    python tools/build_nutrition_db.py \
        --off    raw/en.openfoodfacts.org.products.csv.gz \
        --usda   raw/FoodData_Central_foundation_food_csv_2026-04-30.zip \
        --usda   raw/FoodData_Central_sr_legacy_food_csv_2018-04.zip \
        --ciqual raw/ciqual \
        --bls    raw/bls/BLS_4_0_Daten_2025_DE.csv \
        --bedca  raw/bedca/bedca_foods.csv \
        --cofid  raw/cofid/CoFID_2021.csv \
        --matvaretabellen raw/matvaretabellen \
        --livsmedel raw/swedish \
        --fineli  raw/fineli \
        --frida   raw/frida/FCDB_6.1.csv \
        --swiss  raw/swiss/swiss.csv \
        --tca    raw/tca/tca.csv \
        --cnf    raw/cnf/cnf_2026.zip \
        --afcd   raw/afcd/afcd.csv \
        --schema app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json \
        --out    build/nutrition.db \
        --seed   app/src/main/assets/seed.db \
        --self-check
"""

from __future__ import annotations

import argparse
import copy
import csv
import gzip
import hashlib
import io
import json
import os
import re
import sqlite3
import sys
import time
import unicodedata
import xml.etree.ElementTree as ET
import zipfile

from dataclasses import dataclass, field
from typing import Iterator

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audit_nutrition_db import audit  # noqa: E402  (needs the path above)
from nutrition_values import encode_nutrients  # noqa: E402

csv.field_size_limit(10**9)

# ---------------------------------------------------------------------------
# Identifier ranges
# ---------------------------------------------------------------------------
# Ids are derived from each source's own stable key rather than a running counter, so a food
# keeps the same id across database rebuilds. That matters: a logged diary entry stores
# food_ref_id, and when the food database is later replaced those references must still resolve.
# A counter would silently repoint every historical entry at a different food.
CIQUAL_ID_BASE = 100_000_000            # + alim_code        (< 1e6)
USDA_ID_BASE = 200_000_000              # + fdc_id           (< 3e6)
OFF_ID_BASE = 1_000_000_000_000         # + numeric barcode  (< 1e14)

# ---------------------------------------------------------------------------
# Quality gates
# ---------------------------------------------------------------------------
MIN_NAME_LEN, MAX_NAME_LEN = 2, 120
# Zero is allowed: water, black coffee, tea and diet drinks are genuinely 0 kcal and are
# things people log. The Atwater check below is what rejects a bogus zero -- 0 kcal declared
# alongside real macros fails it -- so a hard floor of 1.0 only threw away valid foods.
MIN_KCAL, MAX_KCAL = 0.0, 900.0
MAX_MACRO_G = 100.0
MAX_MACRO_SUM_G = 105.0
ATWATER_ABS_FLOOR = 35.0
ATWATER_REL_TOLERANCE = 0.30
# Trace threshold for the zero-kcal rule in passes_quality. It matches the audit check's
# own 0.5 g so the gate's leftovers and the audit's count stay one system.
ZERO_KCAL_MACRO_LIMIT = 0.5
VALID_BARCODE_LENGTHS = (8, 12, 13, 14)
# A portion has to be something a person eats: not a microgram, not a whole turkey.
MIN_SERVING_G, MAX_SERVING_G = 1.0, 2000.0
MAX_SERVINGS_PER_FOOD = 8
# The app maps this to a localised string; it must never be shown raw.
CANONICAL_SERVING = "serving"

KCAL_PER_G = {"protein": 4.0, "fat": 9.0, "carbs": 4.0}

# --------------------------------------------------------------------------------------------
# Whole-food ranking signal
# --------------------------------------------------------------------------------------------
# Relevance scoring cannot tell "Apples, raw" from "Apple tart" -- both match "apple" equally,
# and the tart often wins on brevity. Someone typing a plain food name wants the food.
#
# The signal is taken from the best thing each source actually provides:
#
#   Open Food Facts  NOVA processing classification (Monteiro), present on ~36% of usable rows.
#                    Group 1 is unprocessed/minimally processed; group 4 is ultra-processed.
#   USDA / Ciqual    Their documented naming convention. Both describe foods as
#                    "Food, part, preparation state", so the state is carried in the name:
#                    "Apples, raw", "Tomato, green, raw", "Salmon, smoked".
#
# The name-marker lists are a judgement call and are small and legible rather than
# exhaustive. LanguaL would be the rigorous answer -- USDA's SR is fully LanguaL-indexed, with a
# facet for physical form and preparation -- but that indexing is published separately at
# langual.org and is not in the FoodData Central download.

BASIC_RANK_WHOLE = 0        # raw, fresh, plain -- what someone means by the bare food name
BASIC_RANK_ORDINARY = 1     # cooked, prepared, an ordinary product
BASIC_RANK_DERIVATIVE = 2   # an ingredient or preparation made *from* the food

# Words that mark a record as a derivative of the food rather than the food.
#
# Matched as whole words (with an optional trailing plural 's'), never as substrings: `oil` is in
# `boiled`, `jam` in French `jambon`, `paste` in `pasteurized`. `meal` excludes the plural; its one
# compound, cornmeal, is in DERIVATIVE_COMPOUNDS.
DERIVATIVE_MARKERS = (
    "flour", "powder", "starch", "bran", "meal", "isolate", "concentrate", "extract",
    "dried", "dehydrated", "syrup", "nectar", "juice", "paste", "puree", "purée",
    "tart", "pie", "cake", "biscuit", "cookie", "pastry", "candy", "jam", "jelly", "oil",
)
# `biscuit` names two different foods and which one depends on where the row came from.
# In American English a biscuit is a savoury scone -- the thing under the sausage in a
# McDonald's Sausage Biscuit -- while in every other source here it is a sweet cookie.
# The BRITISH_TERMS comment further down refuses the biscuit/cookie alias pair for exactly
# this reason; this is the same divergence reaching the ranking instead of the aliases.
#
# Of the 145 generic-source rows that `biscuit` demotes on
# its own, with no other marker firing:
#
#   ciqual  67 / 67  correct -- the source literally glosses them "Biscuit (cookie)"
#   bls     41 / 41  correct -- Shortbread, Printen, Springerle, all sweet
#   usda     8 / 37  correct -- the other 29 are fast-food breakfast sandwiches,
#                               refrigerated dough, and two breakfast cereals whose
#                               unit happens to be called a biscuit
#
# So the marker is right everywhere except the one American-English generic table, and it
# is switched off there rather than deleted. Adding `cookie` above is what keeps the eight
# genuine USDA cookies demoted: "Cookies, Marie biscuit" and friends carry both words.
#
# This does not reach Open Food Facts. OFF holds every country in one table, so American
# biscuit products there keep the wrong rank; fixing that needs a country signal the database does not store.
SOURCE_EXEMPT_MARKERS: dict[str, frozenset[str]] = {
    "usda": frozenset({"biscuit"}),
}
# Compounds that contain a marker and are themselves derivatives, hand-classified
# against the shipped seed: Cheesecake, Cornflour and Oatbran must keep
# the derivative rank a plain word-boundary match would take away. Data-driven, not
# exhaustive: add a compound only when rows in the data need it.
DERIVATIVE_COMPOUNDS = (
    # cake: every compound cake in the data is a cake
    "cheesecake", "cheescake", "pancake", "oatcake", "fishcake", "hotcake", "minicake",
    "teacake", "rice-cake", "plumcake", "shortcake",
    # flour / starch / bran / dried
    "cornflour", "cornstarch", "oatbran", "sundried",
    # meal: cornmeal only, the ground cereal. Wholemeal and oatmeal are adjectives on breads.
    # Known collateral: two USDA breads named after cornmeal demote with it.
    "cornmeal",
    # tart: tarte/tartine/tartelette are pastries, spreads and dishes; the
    # unrelated words (tartare, tartar sauce, tartufo) are not here
    "tarte", "tartelette", "tartelete", "tartelle", "tarta", "tartine", "tartiner",
    "tartinade", "tartinable", "tartinata", "tartinette", "tartiflette", "tarteteig",
    # one-offs present in the data, each a derivative; some are source typos
    # ("cheescake", "pastryt") or concatenations ("nectarde" = "Nectar de")
    "brandade", "tomatenpuree", "powdered", "jellybean", "candymix", "currypaste",
    "sesampaste", "tomatjuice", "pastete", "pastel", "biscuiti", "biscuithé",
    "boterbiscuit", "syrupwith", "concentrated", "pastryt", "nectarde", "starchy",
)
# An 's' is consumed only when it ends the word, so "oils" matches and "oilseed"
# does not; a hyphen or comma counts as a word edge, exactly as it does for the
# British vocabulary patterns below.
def _compile_markers(markers: tuple[str, ...]) -> re.Pattern[str]:
    return re.compile(
        r"(?<![a-z])(?:"
        + "|".join(
            sorted(
                re.escape(m) + (r"(?:s(?![a-z]))?(?![a-z])" if m != "meal" else r"(?![a-z])")
                for m in markers
            )
        )
        + r")"
    )


_DERIVATIVE_MARKER_RE = _compile_markers(DERIVATIVE_MARKERS)
# One pattern per source that exempts a marker, built once rather than filtered per row --
# basic_rank runs 872,768 times in a full build.
_MARKER_RE_BY_SOURCE: dict[str, re.Pattern[str]] = {
    source: _compile_markers(tuple(m for m in DERIVATIVE_MARKERS if m not in exempt))
    for source, exempt in SOURCE_EXEMPT_MARKERS.items()
}
_DERIVATIVE_COMPOUND_RE = re.compile(
    r"(?<![a-z])(?:" + "|".join(sorted(re.escape(c) + r"(?:s(?![a-z]))?(?![a-z])" for c in DERIVATIVE_COMPOUNDS)) + r")"
)
# Words that mark the plainest form of a food.
#
# Word-anchored, not comma-anchored: sources that do not put a comma before qualifiers must still
# be able to mark a whole food.
WHOLE_FOOD_MARKERS = (
    "raw", "fresh", "whole", "unprocessed", "plain",
    # Portuguese for "raw". TCA names are Portuguese only (load_tca sets lang='pt'), so an
    # English-only list left almost every TCA row "ordinary" and basic_rank told them apart by
    # nothing. "cru"/"crua" describes the dish's own state (raw chicken, raw egg, raw beef cuts).
    # "fresco"/"fresca" is not here: in Portuguese and Spanish it often qualifies one ingredient
    # of a cooked dish ("Bacalao, fresco, al horno" is baked), and it pulled "Atum fresco, bife
    # estufado..." into `azeite` results. The derivative check still runs first, so this can
    # only promote a row, never demote one. No always-English source contains these words.
    "cru", "crua", "crus", "cruas",
)
_WHOLE_FOOD_RE = re.compile(
    r"(?<![a-z])(?:" + "|".join(sorted(WHOLE_FOOD_MARKERS)) + r")(?![a-z])"
)




@dataclass
class Food:
    id: int
    name: str
    brand: str | None
    barcode: str | None
    kcal: float
    protein: float
    fat: float
    carbs: float

    # Which definition `carbs` is, per publisher register and file-side evidence:
    # "by_difference" (fibre included; usda, cnf), "available" (fibre excluded; ten sources and
    # OFF's packaging-label default), or "available_monosaccharide" (CoFID only -- a different
    # scale no fibre arithmetic reaches). Never derived here; each loader states its own.
    carbs_convention: str = "available"

    fiber: float | None = None
    sugar: float | None = None
    sat_fat: float | None = None
    sodium: float | None = None
    density: float | None = None
    source: str = "off"

    # The country a per-user preference can match: a generic row's national table
    # (GENERIC_SOURCE_COUNTRY) or an OFF row's first-listed countries_tags code.
    # NULL means no preference applies -- see off_country() for the OFF half.
    country: str | None = None

    quality: int = 0
    basic: int = BASIC_RANK_ORDINARY
    popularity: int = 0
    nova: int | None = None
    servings: list[tuple[str, float]] = field(default_factory=list)

    # Language of `name`, or None for Open Food Facts, whose product names come off the packaging
    # in whatever language that happens to be -- the export carries no language column, so
    # claiming one would be a guess. Only the generic sources can answer this honestly.
    lang: str | None = None

    # Head words recovered from compound words in `name`, for the FTS index only.
    # Never displayed, never tiered. See split_compounds().
    search_extra: str | None = None

    # Alternate names this food can be found by: (lang, name). How one database serves every
    # language without a row per food per language -- "oeuf" and "papa" resolve to the same
    # measured record rather than to translated duplicates that then drift apart.
    aliases: list[tuple[str, str]] = field(default_factory=list)

    # Explicit denominator and selected value/qualification metadata. Legacy defaults preserve
    # all existing sources; original nutrient columns keep their names for compatibility.
    nutrition_basis: str = "g"
    nutrition_metadata: dict | None = None


class Rejections:
    """Counts why rows were dropped. Printed at the end so the filters stay honest."""

    def __init__(self) -> None:
        self.counts: dict[str, int] = {}
        self.seen = 0

    def reject(self, reason: str) -> None:
        self.counts[reason] = self.counts.get(reason, 0) + 1

    def report(self, label: str, kept: int) -> None:
        print(f"\n  {label}: read {self.seen:,}, kept {kept:,}")
        for reason, n in sorted(self.counts.items(), key=lambda kv: -kv[1]):
            print(f"      rejected {n:>9,}  {reason}")


def load_nevo(path: str) -> list[Food]:
    """Strict source-field reader; validation completes before any database output is touched."""
    foods = [Food(**record) for record in read_nevo(path)]
    # Apply the existing British vocabulary only to the publisher's English alias.
    for food in foods:
        english = next((name for lang, name in food.aliases if lang == "en"), food.name)
        british = british_name(english)
        if british and british not in {english, food.name}:
            food.aliases.append(("en-GB", british))
    return foods


def load_foodfiles(path: str, report: dict | None = None) -> list[Food]:
    """Source reader and explicit omissions; no legacy name/nutrient repair."""
    foods = [Food(**record) for record in read_foodfiles(path, report)]
    for food in foods:
        british = british_name(food.name)
        if british and british != food.name and british not in {name for _, name in food.aliases}:
            food.aliases.append(("en-GB", british))
    return foods


# ---------------------------------------------------------------------------
# Parsing helpers
# ---------------------------------------------------------------------------

_TRACE_RE = re.compile(r"^\s*<\s*([\d.,]+)\s*$")


def to_float(raw: str | None) -> float | None:
    """Tolerant numeric parse.

    Ciqual writes European decimals and non-numeric markers: '-' for missing, 'traces', and
    '< 0,5' for below-detection. Of 13,936 Ciqual macro/energy values, 591 take one of these
    forms. Calling float() directly crashes or silently drops those foods entirely.
    """
    if raw is None:
        return None
    s = raw.strip()
    if not s or s == "-":
        return None
    if s.lower() in ("traces", "trace"):
        return 0.0
    m = _TRACE_RE.match(s)
    if m:  # below detection limit: take half, the usual convention
        try:
            return float(m.group(1).replace(",", ".")) / 2.0
        except ValueError:
            return None
    try:
        v = float(s.replace(",", "."))
    except ValueError:
        return None
    return v if v == v and abs(v) != float("inf") else None


def fold_name(name: str) -> str:
    """Case- and accent-insensitive form of a name, stored as `foods.name_folded`.

    Exists because **SQLite's built-in LOWER() folds ASCII only**: LOWER('EPINARDS') with an
    accented E comes back unchanged, so an accented name could never satisfy the LIKE tiers that
    rank search results and sank to the bottom of every list. Invisible while every name was
    English; the difference between a working and a broken result list once French, German and
    Spanish names exist.

    Punctuation is kept. The ranking turns on the comma in "Butter, salted" -- the
    USDA and Ciqual convention where a comma means qualifiers follow and a space means a compound
    noun -- which is what separates butter from butter beans.

    `foldForSearch` in app/src/main/java/com/lethio/macros/data/food/NameFolding.kt is the other
    half of this and **must agree exactly**, since one folds the stored name and the other folds
    the query. Category 'Mn' rather than unicodedata.combining() is what matches Kotlin's
    CharCategory.NON_SPACING_MARK: some non-spacing marks have a combining class of 0, so the two
    tests are not the same test.
    """
    stripped = "".join(
        ch
        for ch in unicodedata.normalize("NFKD", name)
        if unicodedata.category(ch) != "Mn"
    )
    # Lowercase **after** normalising, not before. NFKD can introduce uppercase out of characters
    # that had none: the trademark sign decomposes to "TM" and the numero sign to "No". Folding
    # first left four rows in the real database reading "ayamTM", which no folded query can equal.
    return "".join(LIGATURES.get(ch, ch) for ch in stripped.lower())


# Single characters that stand for two letters, and that NFKD does *not* split -- Unicode treats
# them as letters in their own right, not as accented forms. Without this, Ciqual's actual name for
# a raw egg, "Oeuf, cru" written with the oe ligature, is unreachable by anyone typing "oeuf": the
# ligature survives folding, so neither the ranking comparison nor the FTS token matches. Same for
# German "Weissbrot" typed against a name spelled with the sharp s.
#
# Two groups, and the rule is what a reader would type on a keyboard that lacks the character.
#
# **Digraphs** stand for two letters, so they fold to two letters. NFKD does not split them --
# Unicode treats them as letters in their own right.
#
# **Stroked letters** fold to their base letter. Strictly that is transliteration rather than
# folding, but NFKD already strips the ring from a-ring and the ae ligature already folds, so
# without it o-slash would be the one Nordic letter unreachable from an ASCII keyboard: "blabar"
# would find blaabaer while "rodbete" found nothing. About a third of the Norwegian and Danish
# aliases contain o-slash.
#
# The base letter, not the traditional digraph: o-slash folds to "o" rather than "oe" because
# a-ring already folds to "a" rather than "aa", and someone typing on an English keyboard reaches
# for the bare letter. Collisions this creates are harmless -- both spellings still find the row.
LIGATURES = {
    # Digraphs: one character, two letters.
    "œ": "oe",   # oe ligature
    "æ": "ae",   # ae ligature
    "ß": "ss",   # sharp s
    "ĳ": "ij",   # ij ligature
    # Stroked letters: one character, one letter, a stroke through it.
    "ø": "o",    # o-slash, Norwegian and Danish
    "ł": "l",    # l-stroke, Polish
    "ð": "d",    # eth, Icelandic and Faroese
}


# National tables rank ahead of packaged products: someone typing one plain word wants the food.
# Assigned here so every new national table is generic automatically. The source vocabulary is
# imported from audit_nutrition_db, so the build and the audit cannot disagree.
from audit_nutrition_db import GENERIC_SOURCES  # noqa: E402  (needs the path above)
from nevo_source import read_nevo  # noqa: E402
from foodfiles_source import read_foodfiles  # noqa: E402
SOURCE_RANK_GENERIC = 0
SOURCE_RANK_BRANDED = 1


def source_rank(source: str) -> int:
    return SOURCE_RANK_GENERIC if source in GENERIC_SOURCES else SOURCE_RANK_BRANDED


# USDA's food_portion table mixes genuinely named portions ("croissant, medium") with plain
# unit conversions ("oz", "cup"). The latter are not servings -- the app already offers grams and
# ounces natively -- and a portion labelled "oz" collides with the built-in unit, showing up twice
# in the picker and, worse, becoming the default so a croissant defaults to "1 oz".
DEGENERATE_SERVING_LABELS = {
    "g", "gram", "grams", "kg", "oz", "ounce", "ounces", "fl oz", "fl. oz",
    "ml", "millilitre", "milliliter", "l", "litre", "liter", "lb", "pound",
    "quantity not specified", "undetermined",
}


# Portion labels are shown to users in ten locales, so a label must either be a token the app can
# translate or a word it can pass through. USDA's raw labels are neither: they are English,
# imperial and verbose -- 'medium (7" to 7-7/8" long)', 'piece, cooked, excluding refuse (yield
# from 1 lb raw meat with refuse...)'.
#
# Normalising to a small canonical vocabulary means the app can translate the common cases and
# fall back to the raw text for the long tail. The gram weight is always shown alongside, so even
# an untranslated or unfamiliar portion stays usable.
CANONICAL_PORTIONS = {
    "serving": ("serving", "servings", "nlea serving", "pack serving", "portion"),
    "cup": ("cup", "cups"),
    "tablespoon": ("tbsp", "tablespoon", "tablespoons"),
    "teaspoon": ("tsp", "teaspoon", "teaspoons"),
    "slice": ("slice", "slices"),
    "piece": ("piece", "pieces"),
    "unit": ("unit", "units", "each"),
    "bar": ("bar", "bars"),
    "bottle": ("bottle", "bottles"),
    "can": ("can", "cans"),
    "package": ("package", "packages", "pack", "packet"),
    "small": ("small",),
    "medium": ("medium", "med"),
    "large": ("large",),
}
_CANONICAL_LOOKUP = {
    variant: token for token, variants in CANONICAL_PORTIONS.items() for variant in variants
}
_PARENTHETICAL = re.compile(r"\s*\([^)]*\)")


def normalize_serving_label(label: str) -> str | None:
    """Reduces a source portion label to a canonical token, or a short readable fallback.

    Returns None if nothing usable survives.
    """
    cleaned = _PARENTHETICAL.sub("", label).strip().strip(",").strip()
    if not cleaned:
        return None

    lowered = cleaned.lower()
    if lowered in _CANONICAL_LOOKUP:
        return _CANONICAL_LOOKUP[lowered]

    # "cup, mashed" / "medium, cooked": the head noun carries the meaning, the rest is detail
    # that would be untranslatable anyway.
    head = lowered.split(",")[0].strip()
    if head in _CANONICAL_LOOKUP:
        return _CANONICAL_LOOKUP[head]

    # Long tail: keep it, but only if it is short enough to render on a chip.
    return cleaned if len(cleaned) <= 24 else None


def is_named_serving(label: str) -> bool:
    """A serving worth offering names a real-world portion, not a unit of measure."""
    return label.strip().lower().rstrip(".") not in DEGENERATE_SERVING_LABELS


_HTML_ENTITY = re.compile(r"&(?:amp|quot|apos|lt|gt|nbsp|#\d+|#x[0-9a-fA-F]+);")
_URL = re.compile(r"https?://|www\.", re.IGNORECASE)


def clean_name(raw: str | None) -> str | None:
    """Normalises a display name, or rejects it as unusable.

    Source names arrive with HTML entities left in by scrapers, stray control characters from
    bad encodings, and occasional double-encoded UTF-8. None of that is visible to whoever
    entered it, but all of it renders literally in search results.
    """
    if not raw:
        return None

    name = raw
    # Double-encoded UTF-8 ("CÃ´te" for "Côte"). Only accept the repair if it removes the
    # tell-tale sequences, so correctly-encoded text is never mangled by a bad round-trip.
    if "Ã" in name or "â€" in name:
        try:
            repaired = name.encode("cp1252", errors="strict").decode("utf-8", errors="strict")
            if "Ã" not in repaired and "â€" not in repaired:
                name = repaired
        except (UnicodeEncodeError, UnicodeDecodeError):
            pass

    name = _HTML_ENTITY.sub(" ", name)
    name = "".join(c for c in name if unicodedata.category(c) != "Cc")
    name = " ".join(name.split())

    if not (MIN_NAME_LEN <= len(name) <= MAX_NAME_LEN):
        return None
    if _URL.search(name):
        return None
    # A name with no letters at all is a barcode, a weight, or a scanning artefact.
    if not any(c.isalpha() for c in name):
        return None
    return name


def gtin_check_digit_ok(code: str) -> bool:
    """Validates the GTIN-8/12/13/14 check digit.

    Digits and length alone are not enough: 3.7% of otherwise well-formed barcodes in Open Food
    Facts fail this, and they are internal or mistyped codes that no scanner will ever produce.
    They cannot be found by scanning, so they are dead weight that only pollutes search.
    """
    digits = [int(c) for c in code]
    body, check = digits[:-1], digits[-1]
    total = sum(d * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return (10 - total % 10) % 10 == check


def clean_barcode(raw: str | None) -> str | None:
    if not raw:
        return None
    code = raw.strip()
    if not (code.isdigit() and len(code) in VALID_BARCODE_LENGTHS):
        return None
    return code if gtin_check_digit_ok(code) else None


def passes_quality(kcal, protein, fat, carbs) -> str | None:
    """Returns a rejection reason, or None if the row is acceptable."""
    if kcal is None:
        return "no energy value"
    if not (MIN_KCAL <= kcal <= MAX_KCAL):
        return f"energy out of range ({MIN_KCAL:g}-{MAX_KCAL:g} kcal/100g)"
    if None in (protein, fat, carbs):
        return "incomplete macros"
    if not all(0 <= v <= MAX_MACRO_G for v in (protein, fat, carbs)):
        return "macro out of range (0-100 g/100g)"
    if protein + fat + carbs > MAX_MACRO_SUM_G:
        return "macros sum above 105 g/100g"
    # A zero-kcal food cannot declare protein or fat above trace level: both always carry
    # calories (4 and 9 kcal/g) and have no zero-calorie analogue the way sugar alcohols are
    # one for carbohydrate. The general Atwater tolerance below never catches this class --
    # its 35 kcal absolute floor lets a row declare up to 8.75 g of protein at 0 kcal and
    # still pass. Carbohydrate is exempt on purpose: declared carbs include fibre and
    # polyols, which genuinely round to zero energy (konjac noodles, erythritol, diet
    # drinks), and the shipped data is full of them.
    if kcal == 0 and (protein > ZERO_KCAL_MACRO_LIMIT or fat > ZERO_KCAL_MACRO_LIMIT):
        return "zero energy with declared protein or fat"
    predicted = (
        protein * KCAL_PER_G["protein"]
        + fat * KCAL_PER_G["fat"]
        + carbs * KCAL_PER_G["carbs"]
    )
    allowed = max(ATWATER_ABS_FLOOR, ATWATER_REL_TOLERANCE * kcal)
    if abs(kcal - predicted) > allowed:
        # Catches kilojoules stored as kilocalories and macros scaled by 100.
        return "fails Atwater cross-check (energy vs macros)"
    return None


_WHOLE_MILK_RE = re.compile(r"(?<![a-z])whole milk(?![a-z])")
_IS_MILK_RE = re.compile(r"milk(?![a-z])|whole milk(?! *[a-z])")


def _without_whole_milk_ingredient(lowered: str) -> str:
    """Drop "whole milk" where it names an ingredient, so it cannot mark the food itself whole.

    "Puddings, vanilla, dry mix, prepared with whole milk" and "Yoghurt Bulgarian whole
    milk" scored whole on the word "whole". When the food IS milk -- the name starts with "milk",
    or with "whole milk" followed by a comma or nothing ("Whole milk, pasteurized") -- the phrase
    is kept. "Whole milk chocolate" is chocolate, so there it is an ingredient too.
    """
    if _IS_MILK_RE.match(lowered):
        return lowered
    return _WHOLE_MILK_RE.sub(" ", lowered)


def basic_rank(name: str, source: str, nova: int | None) -> int:
    lowered = name.lower()
    markers = _MARKER_RE_BY_SOURCE.get(source, _DERIVATIVE_MARKER_RE)
    if markers.search(lowered) or _DERIVATIVE_COMPOUND_RE.search(lowered):
        return BASIC_RANK_DERIVATIVE
    if markers is not _DERIVATIVE_MARKER_RE and _DERIVATIVE_MARKER_RE.search(lowered):
        # An exempted marker matched and nothing else did, so this row is not a derivative
        # in this source's dialect. It is not a *whole food* either, and saying so is the
        # whole reason this branch exists rather than a plain fall-through: WHOLE_FOOD_MARKERS
        # is checked below and ", plain" is one of them, so falling through promoted seven
        # USDA rows straight from derivative to whole -- including "Biscuits, plain or
        # buttermilk, dry mix", a boxed powder, ranked as though it were the food itself.
        # Two steps up is a worse error than the one step down being repaired here.
        return BASIC_RANK_ORDINARY
    if _WHOLE_FOOD_RE.search(_without_whole_milk_ingredient(lowered)):
        return BASIC_RANK_WHOLE
    if source == "off" and nova is not None:
        if nova == 1:
            return BASIC_RANK_WHOLE
        if nova == 4:
            return BASIC_RANK_DERIVATIVE
    return BASIC_RANK_ORDINARY


def sane_micro(value: float | None, ceiling: float) -> float | None:
    """Drops implausible micronutrient values instead of storing them.

    Only energy and the three macros were validated before, so the reserved micronutrient
    columns quietly accumulated nonsense: sugar of 74,000 g/100g, saturated fat exceeding total
    fat by 2,000 g. Harmless while unused, and a guaranteed bug the day the UI shows them.
    """
    if value is None or value < 0 or value > ceiling:
        return None
    return value


def clean_micros(food: Food) -> None:
    food.fiber = sane_micro(food.fiber, MAX_MACRO_G)
    food.sugar = sane_micro(food.sugar, MAX_MACRO_G)
    food.sat_fat = sane_micro(food.sat_fat, MAX_MACRO_G)
    food.sodium = sane_micro(food.sodium, 45.0)  # pure salt is ~39 g sodium per 100 g
    # A component cannot exceed the total it is part of.
    if food.sugar is not None and food.sugar > food.carbs + 1:
        food.sugar = None
    if food.sat_fat is not None and food.sat_fat > food.fat + 1:
        food.sat_fat = None
    if food.fiber is not None and food.fiber > food.carbs + 100:
        food.fiber = None


# The first serving becomes the default the user sees, so the order is a UX decision, not an
# implementation detail. Source order is arbitrary: it made a banana default to "cup, mashed",
# which is not how anyone logs a banana. Whole-item portions come first, volume measures last --
# volume for a solid is a US convention and a cup is not even the same size in every country.
SERVING_PREFERENCE = (
    "medium", "serving", "piece", "slice", "unit", "small", "large",
    "extra small", "extra large", "bar", "package", "can", "bottle", "jar",
    "steak", "fillet", "roast",
)
VOLUME_PORTIONS = ("cup", "tablespoon", "teaspoon")


def serving_sort_key(label: str) -> int:
    lowered = label.strip().lower()
    if lowered in SERVING_PREFERENCE:
        return SERVING_PREFERENCE.index(lowered)
    if lowered in VOLUME_PORTIONS:
        return 200 + VOLUME_PORTIONS.index(lowered)
    return 100  # unknown but named: ahead of volume, behind the well-known ones


def clean_servings(servings: list[tuple[str, float]]) -> list[tuple[str, float]]:
    """Keeps only portions a person might actually eat, one per label.

    USDA lists whole-carcass portions ("bird", 5717 g) and OFF sometimes reports a serving
    quantity of 0.000001 g. Either becomes the default portion and produces a nonsense entry.
    """
    seen: set[str] = set()
    out: list[tuple[str, float]] = []
    for label, grams in servings:
        key = label.strip().lower()
        if key in seen or not (MIN_SERVING_G <= grams <= MAX_SERVING_G):
            continue
        seen.add(key)
        out.append((label, grams))
    out.sort(key=lambda pair: serving_sort_key(pair[0]))
    return out[:MAX_SERVINGS_PER_FOOD]


def quality_score(food: Food) -> int:
    """0-100 completeness and confidence. Ranks search results and picks the seed subset."""
    score = 40
    if food.brand:
        score += 10
    if food.servings:
        score += 15
    if food.barcode:
        score += 5
    if food.source in GENERIC_SOURCES:
        # Lab-measured national tables, not crowd-sourced labels. Keyed off the shared set so
        # a new table scores like its siblings instead of silently losing the last tiebreak.
        score += 20
    if any(v is not None for v in (food.fiber, food.sugar, food.sat_fat, food.sodium)):
        score += 5
    if len(food.name) >= 8:
        score += 5
    return min(score, 100)


# ---------------------------------------------------------------------------
# British English vocabulary
# ---------------------------------------------------------------------------
# The generic tier (USDA, Ciqual) is named in American English, so the word a British
# user types often appears nowhere in the database: "courgette" misses every record
# filed under Zucchini. Each pair maps an everyday American term to the British one,
# and the rendered name becomes an `en-GB` alias on generic foods only -- Open Food
# Facts names come off real packaging and are never rewritten.
#
# Only pairs that name the same food for certain. US "biscuit" is a savoury scone
# while UK "biscuit" is a cookie; "jelly", "chips", "pudding", "cider", "lemonade"
# and "bacon" diverge the same way, so none of them appear here -- a wrong pair
# poisons search for both countries. The US side of each pair is a regex fragment
# matched between word boundaries, which is what keeps "swede" out of "Swedish" and
# "mince" out of "mincemeat".
BRITISH_TERMS: tuple[tuple[str, str], ...] = (
    ("all-purpose", "plain"),           # US all-purpose flour = UK plain flour
    # UK writes it as two words, but the compound already answers an "apple sauce"
    # prefix query, so this produces no alias today
    ("applesauce", "apple sauce"),
    ("arugula", "rocket"),              # UK rocket (the salad leaf)
    ("baking soda", "bicarbonate of soda"),
    ("beet", "beetroot"),
    ("beets", "beetroots"),
    ("broiled", "grilled"),             # US broiled = UK grilled (under direct heat)
    ("catsup", "ketchup"),              # US regional spelling of the same condiment
    ("cilantro", "coriander"),          # US cilantro = UK coriander (the fresh leaf)
    ("corn, sweet", "sweetcorn"),       # USDA writes the vegetable as "Corn, sweet"
    # UK cornflour is US cornstarch -- NOT US "corn flour", which is fine maize meal
    ("cornstarch", "cornflour"),
    ("eggplant", "aubergine"),
    ("fava", "broad"),                  # US fava bean = UK broad bean
    ("frosting", "icing"),
    ("garbanzo", "chickpea"),
    ("gelatin", "gelatine"),            # UK spelling
    # Ground meat only: USDA puts the cut first ("Beef, ground, 70% lean"), and the
    # comma is what tells that apart from a spice ("Spices, nutmeg, ground").
    ("ground(?=,)", "minced"),
    ("ground beef", "minced beef"),     # the "ground X" word order the comma rule misses
    ("ground lamb", "minced lamb"),
    ("lima", "butter"),                 # US lima bean = UK butter bean
    ("molasses", "treacle"),
    ("navy", "haricot"),                # US navy bean = UK haricot bean
    ("romaine", "cos"),                 # UK cos lettuce
    ("rutabaga", "swede"),
    ("rutabagas", "swedes"),
    ("scallion", "spring onion"),
    ("scallions", "spring onions"),
    ("self-rising", "self-raising"),    # UK spelling
    ("shrimp", "prawn"),
    ("shrimps", "prawns"),
    ("skim", "skimmed"),                # US skim milk = UK skimmed milk
    ("snow pea", "mangetout"),
    ("Sugars, powdered", "Icing sugar"),  # US powdered sugar = UK icing sugar
    ("whole wheat", "wholemeal"),
    ("whole-wheat", "wholemeal"),
    ("yogurt", "yoghurt"),              # UK spelling
    ("zucchini", "courgette"),
)

_BRITISH_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = tuple(
    (re.compile(r"\b" + us + r"\b", re.IGNORECASE), gb) for us, gb in BRITISH_TERMS
)


def british_name(name: str) -> str | None:
    """The British rendering of a food name, or None if no British term applies.

    Substitution rather than translation: "Zucchini, raw" becomes "Courgette, raw",
    so the qualifier survives and the alias still reads as the same food. Every term
    that matches is substituted into one alias, never one alias per term.

    A term is skipped when the British word is already written into the name --
    Ciqual writes "Courgette or zucchini" and "Shrimp or prawn", and an alias
    repeating the British word would only pad the index. Returns None when the name
    is unchanged, or when the result is no longer a clean name (a substitution can
    push a name over the 120-character cap).
    """
    lower = name.lower()
    result = name
    for pattern, gb in _BRITISH_PATTERNS:
        if not pattern.search(name):
            continue
        # The British word already appears in the name, so the name is already
        # findable and an alias would only repeat it. Word-by-word, so "Onions,
        # spring or scallions" counts as already carrying "spring onions".
        if all(w in lower for w in gb.lower().split()):
            continue

        def repl(m: re.Match[str]) -> str:
            # Preserve the case of the matched word: "Zucchini" -> "Courgette".
            return gb[0].upper() + gb[1:] if m.group(0)[0].isupper() else gb

        result = pattern.sub(repl, result)
    out = clean_name(result)
    if out is None or out == name:
        return None
    return out


# ---------------------------------------------------------------------------
# Ciqual
# ---------------------------------------------------------------------------

CIQUAL_CONST = {
    "328": "kcal",      # Energy, EU Regulation 1169/2011 (kcal/100g) -- preferred
    "333": "kcal_alt",  # Energy, N x Jones factor (kcal/100g) -- fallback
    "25000": "protein",
    "31000": "carbs",
    "40000": "fat",
    "34100": "fiber",
    "32000": "sugar",
    "40302": "sat_fat",
    "10110": "sodium",
}


def load_ciqual(directory: str, rej: Rejections) -> list[Food]:
    alim_path = _find(directory, "alim_", ".xml")
    compo_path = _find(directory, "compo_", ".xml")
    if not alim_path or not compo_path:
        print(f"  ! Ciqual XML not found in {directory}, skipping")
        return []

    names: dict[str, str] = {}
    french_names: dict[str, str] = {}
    for _, el in ET.iterparse(alim_path, events=("end",)):
        if el.tag != "ALIM":
            continue
        d = {c.tag: (c.text or "").strip() for c in el}
        # alim_nom_eng exists alongside alim_nom_fr, so Ciqual needs no translation pass -- an
        # early plan assumed it did, and that assumption cost a round of unnecessary design.
        # The French name is kept rather than discarded: it becomes a search-only alias, so a
        # French query ("oeuf") reaches the same measured record an English one ("Egg, raw") does.
        fr_name = clean_name(d.get("alim_nom_fr"))
        name = clean_name(d.get("alim_nom_eng")) or fr_name
        if name and d.get("alim_code"):
            names[d["alim_code"]] = name
        if fr_name and d.get("alim_code"):
            french_names[d["alim_code"]] = fr_name
        el.clear()

    values: dict[str, dict[str, float]] = {}
    for _, el in ET.iterparse(compo_path, events=("end",)):
        if el.tag != "COMPO":
            continue
        d = {c.tag: (c.text or "").strip() for c in el}
        key = CIQUAL_CONST.get(d.get("const_code", ""))
        if key:
            v = to_float(d.get("teneur"))
            if v is not None:
                values.setdefault(d["alim_code"], {})[key] = v
        el.clear()

    foods: list[Food] = []
    for code, name in names.items():
        rej.seen += 1
        v = values.get(code, {})
        kcal = v.get("kcal") or v.get("kcal_alt")
        reason = passes_quality(kcal, v.get("protein"), v.get("fat"), v.get("carbs"))
        if reason:
            rej.reject(reason)
            continue
        try:
            fid = CIQUAL_ID_BASE + int(code)
        except ValueError:
            rej.reject("non-numeric alim_code")
            continue
        # Attached at construction rather than in the parse loop, so only rows that survived
        # the quality filter above get one -- an alias naming a rejected row would point at a
        # food that is not in the database. Skipped when the French name reads the same as the
        # English one: loanwords like Miso and Tempeh are identical in both columns, and an
        # alias equal to its food's name would only pad food_aliases and the FTS index.
        fr_name = french_names.get(code)
        foods.append(
            Food(
                id=fid, name=name, brand=None, barcode=None, lang="en",
                kcal=kcal, protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # Available carbohydrate: no definition attached to "Glucides" in Ciqual's own
                # register, but mass closure on 2,810 foods confirms it.
                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),
                # const 10110 is "Sodium (mg/100g)". Stored unconverted this read 39,100 for
                # table salt -- 1000x out, and the same mistake USDA's mg column invites.
                sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                source="ciqual",
                aliases=[("fr", fr_name)] if fr_name and fr_name != name else [],
            )
        )
    return foods


def _find(directory: str, prefix: str, suffix: str) -> str | None:
    if not os.path.isdir(directory):
        return None
    for fn in sorted(os.listdir(directory)):
        if fn.startswith(prefix) and fn.endswith(suffix):
            return os.path.join(directory, fn)
    return None


# ---------------------------------------------------------------------------
# USDA FoodData Central
# ---------------------------------------------------------------------------

USDA_NUTRIENTS = {
    "1008": "kcal", "1003": "protein", "1004": "fat", "1005": "carbs",
    "1079": "fiber", "2000": "sugar", "1258": "sat_fat", "1093": "sodium",
}
# Only these are consumer-facing foods. The Foundation zip contains 87,990 rows of which just
# 469 are foundation_food; the rest are individual lab specimens averaged into those 469.
# Taking the file at face value produces tens of thousands of duplicate entries.
USDA_KEEP_TYPES = {"foundation_food", "sr_legacy_food"}


def load_usda(zip_paths: list[str], rej: Rejections) -> list[Food]:
    foods: list[Food] = []
    for zp in zip_paths:
        if not os.path.exists(zp):
            print(f"  ! {zp} not found, skipping")
            continue
        with zipfile.ZipFile(zp) as z:
            base = os.path.commonprefix([n for n in z.namelist() if "/" in n])
            base = base[: base.rfind("/") + 1] if "/" in base else ""

            def rows(name: str):
                try:
                    with z.open(base + name) as fh:
                        yield from csv.DictReader(
                            io.TextIOWrapper(fh, "utf-8", errors="replace")
                        )
                except KeyError:
                    return

            keep: dict[str, str] = {}
            for r in rows("food.csv"):
                if r.get("data_type") in USDA_KEEP_TYPES:
                    name = clean_name(r.get("description"))
                    if name:
                        keep[r["fdc_id"]] = name

            nutrients: dict[str, dict[str, float]] = {}
            for r in rows("food_nutrient.csv"):
                fid = r.get("fdc_id")
                if fid not in keep:
                    continue
                key = USDA_NUTRIENTS.get(r.get("nutrient_id", ""))
                if key:
                    v = to_float(r.get("amount"))
                    if v is not None:
                        nutrients.setdefault(fid, {})[key] = v

            units = {r["id"]: r["name"] for r in rows("measure_unit.csv")}
            portions: dict[str, list[tuple[str, float]]] = {}
            for r in rows("food_portion.csv"):
                fid = r.get("fdc_id")
                if fid not in keep:
                    continue
                grams = to_float(r.get("gram_weight"))
                if not grams or grams <= 0:
                    continue
                label = (
                    (r.get("portion_description") or "").strip()
                    or " ".join(
                        p for p in (
                            units.get(r.get("measure_unit_id", ""), ""),
                            (r.get("modifier") or "").strip(),
                        ) if p and p != "undetermined"
                    ).strip()
                )
                normalized = normalize_serving_label(label) if label else None
                if normalized and is_named_serving(normalized):
                    portions.setdefault(fid, []).append((normalized, grams))

            for fid, name in keep.items():
                rej.seen += 1
                v = nutrients.get(fid, {})
                reason = passes_quality(
                    v.get("kcal"), v.get("protein"), v.get("fat"), v.get("carbs")
                )
                if reason:
                    rej.reject(reason)
                    continue
                # Sodium is milligrams in USDA, grams elsewhere.
                sodium = v.get("sodium")
                foods.append(
                    Food(
                        id=USDA_ID_BASE + int(fid), name=name, brand=None, barcode=None,
                        lang="en",
                        kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                        # nutrient.csv id 1005 = "Carbohydrate, by difference".
                        carbs_convention="by_difference",
                        fiber=v.get("fiber"), sugar=v.get("sugar"),
                        sat_fat=v.get("sat_fat"),
                        sodium=sodium / 1000.0 if sodium is not None else None,
                        source="usda",
                        servings=clean_servings(portions.get(fid, [])),
                    )
                )
    return foods


# ---------------------------------------------------------------------------
# German BLS (Bundeslebensmittelschluessel)
# ---------------------------------------------------------------------------

# `BLS Code` is a 7-character alphanumeric string (C131000, M5B1600), not the numeric key
# the other sources carry, so `BLS_ID_BASE + int(code)` cannot be done directly. Decoding
# the code as base 36 is injective on its [A-Z0-9] alphabet and a pure function of the
# code, so ids stay stable across rebuilds. Measured across all 7,140 codes,
# int(code, 36) runs 24,005,118,528..74,571,684,660, so BLS ids land in
# [BLS_ID_BASE, BLS_ID_BASE + 75e9): above USDA's and far below Open Food Facts'.
BLS_ID_BASE = 300_000_000

# Header text carries the unit ([g/100g] vs [mg/100g]), so columns are looked up by the
# leading nutrient code the Components dictionary defines; the rest is documentation.
BLS_COLUMNS = {
    "ENERCC": "kcal",
    "PROT625": "protein",
    "FAT": "fat",
    "CHO": "carbs",
    "FIBT": "fiber",
    "SUGAR": "sugar",
    "FASAT": "sat_fat",
    "NA": "sodium",
}


def _bls_value(raw: str | None) -> float | None:
    """Parses a BLS value cell.

    BLS writes 'TR' where Ciqual writes 'traces' (present, amount unknown), and to_float()
    maps traces to 0.0 -- the same convention applies here. The other markers ('-',
    '<LOD', '<LOQ', '<LOD or <LOQ') mean not measured and already parse to None.
    """
    if raw is None:
        return None
    s = raw.strip()
    if s == "TR":
        return 0.0
    return to_float(s)


def load_bls(path: str, rej: Rejections) -> list[Food]:
    """Loads the CSV written by tools/extract_bls_csv.py."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        cols = {h.split(" ", 1)[0]: h for h in reader.fieldnames}

        foods: list[Food] = []
        for r in reader:
            rej.seen += 1
            en_name = clean_name(r[cols["Food"]])
            de_name = clean_name(r[cols["Lebensmittelbezeichnung"]])
            # Both names are populated on all 7,140 rows, but 5 name cells exceed the
            # 120-char limit clean_name() rejects, so English first with the German name
            # as fallback, exactly as Ciqual falls back to French.
            name = en_name or de_name
            if not name:
                rej.reject("missing or unusable name")
                continue

            v = {key: _bls_value(r[cols[code]]) for code, key in BLS_COLUMNS.items()}
            reason = passes_quality(v["kcal"], v["protein"], v["fat"], v["carbs"])
            if reason:
                rej.reject(reason)
                continue
            try:
                fid = BLS_ID_BASE + int(r[cols["BLS"]], 36)
            except ValueError:
                rej.reject("non-base36 BLS Code")
                continue

            # CHO is available carbohydrate (fibre excluded) -- the schema's carbs. The
            # Atwater gate catches the fibre-heavy rows this understates, such as wheat bran.
            foods.append(
                Food(
                    id=fid, name=name, brand=None, barcode=None, lang="en",
                    kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                    # CHO = SUGAR+STARCH+OLSAC+POLYL exactly, fibre outside it.
                    carbs_convention="available",
                    fiber=v.get("fiber"), sugar=v.get("sugar"),
                    sat_fat=v.get("sat_fat"),
                    # `NA Natrium [mg/100g]` is milligrams, like USDA's column and like
                    # Ciqual's const 10110; stored grams, so divide by 1000 exactly as
                    # both siblings do.
                    sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                    source="bls",
                    # Attached only to rows that survived the quality filter, and skipped
                    # when the German name reads the same as the stored one (34 loanword
                    # rows: Tofu, Gin, Salami).
                    aliases=[("de", de_name)] if de_name and de_name != name else [],
                )
            )
        return foods


# ---------------------------------------------------------------------------
# Spanish BEDCA (Base de Datos Espanola de Composicion de Alimentos)
# ---------------------------------------------------------------------------

# `f_id` is numeric and unique (680..2713), so the id is `base + f_id`. 1e11 clears BLS's
# theoretical ceiling (300M + int(code, 36) over 7-character codes, at most 78.7e9) and sits below
# Open Food Facts' 1e12, so the range collides with nothing.
BEDCA_ID_BASE = 100_000_000_000

# Component -> schema field. Every value is read together with its
# row's `_unit` cell and its `moex` basis -- never a per-column unit constant: ENERC carries
# both kJ and kcal, CHO and FIBT carry a handful of mg cells, and NA's unit reads 'mg' on
# rows that are per *kilogram* of edible portion, which only the long file's `moex` reveals.
BEDCA_COLUMNS = {
    "ENERC": "kcal",
    "PROT": "protein",
    "FAT": "fat",
    "CHO": "carbs",
    "FIBT": "fiber",
    "SUGAR": "sugar",
    "FASAT": "sat_fat",
    "NA": "sodium",
}


def _bedca_value(raw: str | None, unit: str, basis: str, code: str) -> float | None:
    """Converts one BEDCA value cell into the schema's unit, or None.

    `basis` is the long file's `moex` for this (f_id, component): W = per 100 g of edible
    portion, WKG = per kg of edible portion. Any other basis -- or an unknown one -- drops
    the value instead of guessing; a basis guessed wrong is wrong for a handful of rows and
    passes every aggregate count, which is the exact failure mode this loader exists to
    avoid. Dropping energy is loud (the food is rejected as having no energy);
    dropping a micro is silently safe. Empty cells are None like every other source.
    """
    v = to_float(raw)
    if v is None:
        return None
    if code == "NA":
        # 'mg' alone says nothing about the basis: W rows are mg/100g, and the 32 WKG rows
        # (f_ids 691..722, the regional cheeses) are mg/kg with the identical unit string.
        if basis == "WKG":
            return v / 10000.0
        if basis != "W":
            return None
        return v / 1000.0
    if basis != "W":
        return None
    if code == "ENERC":
        return v / 4.184 if unit == "kJ" else v
    # The gram columns are g throughout, except one CHO cell and three FIBT cells in mg
    # -- the one class of case where a per-column constant would pass every
    # aggregate count while storing one row 1000x off.
    return v / 1000.0 if unit == "mg" else v


def load_bedca(path: str, rej: Rejections) -> list[Food]:
    """Loads the wide CSV of a BEDCA export."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    # The wide file carries the unit per value but not the basis, so the long file beside it
    # is read for `moex` per (f_id, component). It is the only place that distinguishes the
    # per-kg sodium rows from the per-100 g ones, and the check runs on every mapped component
    # so a future reissue cannot slip a per-ml or dry-weight row into a mapped column quietly.
    long_path = os.path.join(os.path.dirname(os.path.abspath(path)), "bedca_foodvalues.csv")
    moex: dict[tuple[int, str], str] = {}
    if os.path.exists(long_path):
        with open(long_path, encoding="utf-8-sig", newline="") as fh:
            for r in csv.DictReader(fh):
                moex[(int(r["f_id"]), r["eur_name"])] = r["moex"]
    else:
        print(f"  ! {long_path} not found; off-basis values will be dropped")

    wkg_sodium = 0
    dropped_basis = 0
    foods: list[Food] = []
    with open(path, encoding="utf-8-sig", newline="") as fh:  # BOM + CRLF
        reader = csv.DictReader(fh)
        for r in reader:
            rej.seen += 1
            en_name = clean_name(r["f_eng_name"])
            es_name = clean_name(r["f_ori_name"])
            name = en_name or es_name
            if not name:
                rej.reject("missing or unusable name")
                continue
            try:
                fid = int(r["f_id"])
            except ValueError:
                rej.reject("non-numeric BEDCA f_id")
                continue

            def value(code: str) -> float | None:
                nonlocal wkg_sodium, dropped_basis
                raw, unit = r[code], (r[code + "_unit"] or "").strip()
                basis = moex.get((fid, code), "")
                if to_float(raw) is not None:
                    if code == "NA" and basis == "WKG":
                        wkg_sodium += 1
                    elif basis not in ("W", "WKG"):
                        dropped_basis += 1
                return _bedca_value(raw, unit, basis, code)

            v = {key: value(code) for code, key in BEDCA_COLUMNS.items()}
            reason = passes_quality(v["kcal"], v["protein"], v["fat"], v["carbs"])
            if reason:
                rej.reject(reason)
                continue

            # The 12 index rows that carry no composition data never appear in the wide file,
            # so they cannot enter here; the wide file's 957 rows are the whole source.
            foods.append(
                Food(
                    id=BEDCA_ID_BASE + fid, name=name, brand=None, barcode=None, lang="en",
                    kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                    # CHO = SUGAR+STARCH exactly on 163 foods; fibre outside it.
                    carbs_convention="available",
                    fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],
                    # Already grams per 100 g: mg/100g /1000, mg/kg /10000, per row (above).
                    sodium=v["sodium"],
                    source="bedca",
                    # Attached only to rows that survived the quality filter, and skipped
                    # when the Spanish name reads the same as the stored one (24 loanword
                    # rows: Whisky, Kebab, Tofu).
                    aliases=[("es", es_name)] if es_name and es_name != name else [],
                )
            )
    if wkg_sodium or dropped_basis:
        print(f"      moex: {wkg_sodium} per-kg sodium values converted, "
              f"{dropped_basis} values dropped for an off-basis moex")
    return foods


# ---------------------------------------------------------------------------
# UK CoFID (Composition of Foods Integrated Dataset)
# ---------------------------------------------------------------------------

# The food code is McCance & Widdowson numbering, `NN-NNNN`; left*10_000 + right is injective over
# that format (ceiling 999,999). CoFID's band [1.1e11, 1.1e11 + 1e6) clears BEDCA's and leaves
# room below Open Food Facts' 1e12. One code (13-669) names two foods; load_cofid rejects both
# rather than invent a tiebreak.
COFID_ID_BASE = 110_000_000_000

# Column -> schema field. tools/extract_cofid_csv.py verifies these codes against the workbook
# header before writing the CSV. KCALS is read; no 2021 row has energy only in kJ.
COFID_COLUMNS = {
    "KCALS": "kcal",
    "PROT": "protein",
    "FAT": "fat",
    "CHO": "carbs",
    "AOACFIB": "fiber",
    "TOTSUG": "sugar",
    "SATFOD": "sat_fat",
    "NA": "sodium",
}

_PAREN_NUM_RE = re.compile(r"^\(\s*([\d.,]+)\s*\)$")


def _cofid_value(raw: str | None) -> float | None:
    """Parses a CoFID value cell.

    CoFID writes 'Tr' where BLS writes 'TR' and Ciqual 'traces' (present, amount
    unknown); the project convention maps it to 0.0, and this follows it. 'N' is
    CoFID's "no data" marker and maps to None, like BLS's '-'. A parenthesised cell
    is an *estimated* value, a case neither BLS nor BEDCA had: accepted as the
    number, because an estimate is real data and dropping it would store None --
    which for energy rejects the whole food. Zero such cells exist in the 2021
    snapshot; load_cofid prints the count if a reissue introduces any.
    """
    if raw is None:
        return None
    s = raw.strip()
    if s in ("", "N"):
        return None
    if s == "Tr":
        return 0.0
    m = _PAREN_NUM_RE.match(s)
    if m:
        try:
            return float(m.group(1).replace(",", "."))
        except ValueError:
            return None
    return to_float(s)


def load_cofid(path: str, rej: Rejections) -> list[Food]:
    """Loads the CSV written by tools/extract_cofid_csv.py."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    # The code is the id's key, so a code that occurs twice cannot produce two distinct
    # ids -- the 2021 workbook assigns 13-669 to both a roasted-aubergine row and raw
    # watercress. Both rows are rejected: keeping one would need a tiebreak on row order
    # or content, and then the id is no longer a pure function of the code.
    code_counts: dict[str, int] = {}
    for r in rows:
        code = (r["Food Code"] or "").strip()
        code_counts[code] = code_counts.get(code, 0) + 1

    paren = sum(
        1
        for r in rows
        for col in COFID_COLUMNS
        if _PAREN_NUM_RE.match((r[col] or "").strip())
    )

    foods: list[Food] = []
    for r in rows:
        rej.seen += 1
        code = (r["Food Code"] or "").strip()
        if code_counts[code] > 1:
            rej.reject("duplicate food code")
            continue
        name = clean_name(r["Food Name"])
        if not name:
            rej.reject("missing or unusable name")
            continue

        v = {field: _cofid_value(r[col]) for col, field in COFID_COLUMNS.items()}
        reason = passes_quality(v["kcal"], v["protein"], v["fat"], v["carbs"])
        if reason:
            rej.reject(reason)
            continue
        m = re.fullmatch(r"(\d+)-(\d+)", code)
        if not m:
            rej.reject("non-standard food code")
            continue
        foods.append(
            Food(
                id=COFID_ID_BASE + int(m.group(1)) * 10_000 + int(m.group(2)),
                name=name, brand=None, barcode=None, lang="en",
                kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # CHO = sugars+starch+oligo, as monosaccharide equivalents: a different scale from
                # every other source's available carbohydrate.
                carbs_convention="available_monosaccharide",
                fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],
                # `Sodium (mg)` is milligrams, like every other source's column; stored
                # grams, so divide by 1000 exactly as the four siblings do.
                sodium=(v["sodium"] / 1000.0) if v["sodium"] is not None else None,
                source="cofid",
                # CoFID's names are natively British English, so there is no language
                # alias to attach; main()'s en-GB vocabulary pass covers the rest.
                aliases=[],
            )
        )
    if paren:
        print(f"      cofid: {paren} parenthesised (estimated) values accepted as numbers")
    return foods


# ---------------------------------------------------------------------------
# The Nordic four: Matvaretabellen (NO), Livsmedelsdatabasen (SE), Fineli (FI),
# Frida (DK). Each has an id base, column mapping, loader, alias, CLI flag and entries in
# GENERIC_SOURCE_COUNTRY and GENERIC_SOURCES.
#
# Id bases. The four bands sit between CoFID's (which ends below 110_001_000_000)
# and Open Food Facts' 1e12:
#
#   NO  [120_000_000_000, 120_000_100_000)  foodId is NN.NNN, two digits dot
#                                           three, so the whole format space is
#                                           00.000..99.999 < 1e5 -- a real
#                                           theoretical ceiling, not today's
#                                           observed max (13.163).
#   SE  [130_000_000_000, 140_000_000_000)  `nummer` is a plain JSON integer
#                                           with no published upper bound
#                                           (observed 1..7,298); the band admits
#                                           1e10 key values, which the table
#                                           would have to grow by ~1.4 million
#                                           times to outgrow.
#   FI  [140_000_000_000, 150_000_000_000)  FOODID is an integer with no
#                                           published bound (observed 1..35,887,
#                                           4,238 distinct of the space);
#                                           same 1e10 band arithmetic.
#   DK  [150_000_000_000, 160_000_000_000)  FoodID is an integer (observed
#                                           1..2,396, sparse); same 1e10 band
#                                           arithmetic; ~840e9 stays free
#                                           below OFF's 1e12 for the tables
#                                           tables added later.
#
# Non-overlap against every source already loaded: BEDCA ends at
# 100_000_002_713, CoFID's band ends below 110_001_000_000, BLS below 78.7e9,
# USDA below 203e6, Ciqual below 101e6, and OFF starts at 1e12 -- all disjoint
# from [120e9, 160e9). Each id is a pure function of the source's own key, so a
# diary's food_ref_id keeps resolving across rebuilds.
MATVARE_ID_BASE = 120_000_000_000
LIVSMEDEL_ID_BASE = 130_000_000_000
FINELI_ID_BASE = 140_000_000_000
FRIDA_ID_BASE = 150_000_000_000


# ---------------------------------------------------------------------------
# Norway: Matvaretabellen (matvaretabellen.no)
# ---------------------------------------------------------------------------

# nutrientId -> schema field, as found in the snapshot. The constituents list carries 100 nutrientIds and
# **not one of them is energy** -- energy lives in two *top-level* fields on the
# food object, `energy` (kJ) and `calories` (kcal). A loader that reads only
# constituents finds no energy, passes_quality() rejects all 2,121 rows, and
# every count on the way there looks healthy.
MATVARE_COLUMNS = {
    "Protein": "protein",
    "Fett": "fat",
    "Karbo": "carbs",
    "Sukker": "sugar",
    "Fiber": "fiber",
    "Mettet": "sat_fat",
    "Na": "sodium",
}

# foodId is a dotted string NN.NNN, e.g. '06.178', except '01.332 ' which
# carries a trailing space -- the same class of trap as CoFID's 13-669: an id
# derived from an unstripped key is a different id, and diary rows reference
# food_ref_id forever. Strip, validate the format explicitly, reject what does
# not match rather than coercing it.
_MATVARE_ID_RE = re.compile(r"^\d{2}\.\d{3}$")


def _matvare_value(raw_quantity: float | int | None, unit: str | None) -> float | None:
    """One constituent value into the schema's unit.

    Missing means None, never 0: 91,873 of 212,100 records have no `quantity`
    key at all, and `c.get("quantity", 0)` would record a measured **zero** for
    an unmeasured nutrient -- it passes every aggregate check and is wrong in
    the user's diary. The gram columns are g/100g, Na is mg/100g (per-record
    unit, measured: g columns carry 'g', Na carries 'mg').
    """
    if raw_quantity is None:
        return None
    v = to_float(str(raw_quantity))
    if v is None:
        return None
    return v / 1000.0 if unit == "mg" else v


def load_matvaretabellen(directory: str, rej: Rejections) -> list[Food]:
    """Loads the two API snapshots, English and Norwegian, joined on foodId."""
    en_path = os.path.join(directory, "foods_en.json")
    nb_path = os.path.join(directory, "foods_nb.json")
    if not (os.path.exists(en_path) and os.path.exists(nb_path)):
        print(f"  ! foods_en.json / foods_nb.json not found in {directory}, skipping")
        return []

    en = json.load(open(en_path, encoding="utf-8"))["foods"]
    nb = json.load(open(nb_path, encoding="utf-8"))["foods"]

    # Both languages come from separate endpoints; the join relies on the id
    # being present and identical in both documents. Verified in the snapshot
    # (same 2,121 ids, including the one trailing-space dirty id) and asserted
    # here so a reissue whose documents drift fails loudly instead of pairing
    # Norwegian names onto the wrong foods.
    en_ids = {f["foodId"].strip(): f for f in en}
    nb_by_id = {f["foodId"].strip(): f for f in nb}
    if set(en_ids) != set(nb_by_id):
        print(f"  ! foods_en.json and foods_nb.json id sets differ, skipping")
        return []
    if len(en_ids) != len(en) or len(nb_by_id) != len(nb):
        # The dict comprehension above collapses ids that differ only by
        # whitespace; that must never happen silently, because it would fold
        # two foods into one id.
        print(f"  ! foods_en.json / foods_nb.json carry ids that collide after"
              f" stripping, skipping")
        return []

    foods: list[Food] = []
    for raw_id, f in en_ids.items():
        rej.seen += 1
        if not _MATVARE_ID_RE.match(raw_id):
            rej.reject("non-standard foodId")
            continue
        left, right = raw_id.split(".")
        fid = MATVARE_ID_BASE + int(left) * 1000 + int(right)

        name = clean_name(f.get("foodName"))
        nb_name = clean_name(nb_by_id[raw_id].get("foodName"))
        if not name:
            rej.reject("missing or unusable name")
            continue

        constituents = {c.get("nutrientId"): c for c in f.get("constituents", [])}
        v = {
            field: _matvare_value(
                constituents.get(nid, {}).get("quantity"),
                constituents.get(nid, {}).get("unit"),
            )
            for nid, field in MATVARE_COLUMNS.items()
        }
        # Energy is top-level, and the kcal field is read: `calories` carries
        # kcal on all 2,121 foods, while `energy` (kJ) is missing on 3 and
        # kJ/4.184 disagrees with the declared kcal by more than rounding on
        # 532 foods -- the publisher's kcal is authoritative for the Atwater
        # gate, exactly as CoFID reads KCALS rather than converting KJ.
        kcal = f.get("calories", {}).get("quantity")
        reason = passes_quality(kcal, v["protein"], v["fat"], v["carbs"])
        if reason:
            rej.reject(reason)
            continue
        foods.append(
            Food(
                id=fid, name=name, brand=None, barcode=None, lang="en",
                kcal=kcal, protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # kcal = 4P+9F+4*Karbo+2*Fiber, median 0.000 over 2,118 rows --
                # Karbo is available carbohydrate, fibre paid separately.
                carbs_convention="available",
                fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],
                sodium=v["sodium"],
                source="matvaretabellen",
                # Attached only to rows that survived the quality filter, and
                # skipped when the Norwegian name reads the same as the stored
                # one (24 loanword rows).
                aliases=[("nb", nb_name)] if nb_name and nb_name != name else [],
            )
        )
    return foods


# ---------------------------------------------------------------------------
# Sweden: Livsmedelsdatabasen (livsmedelsverket.se)
# ---------------------------------------------------------------------------

# euroFIRkod -> schema field. The codes are the same vocabulary BEDCA uses, so
# `_bedca_value` -- the per-row unit and basis handling -- applies
# verbatim: read nothing off a non-W basis, mg->g for sodium, and never a
# per-column unit constant.
LIVSMEDEL_COLUMNS = {
    "ENERC": "kcal",
    "PROT": "protein",
    "FAT": "fat",
    "CHO": "carbs",
    "FIBT": "fiber",
    "SUGAR": "sugar",
    "FASAT": "sat_fat",
    "NA": "sodium",
}


def load_livsmedel(directory: str, rej: Rejections) -> list[Food]:
    """Loads the harvested snapshot: two language files joined on `nummer`,
    nutrient values from the per-food naringsvarden JSONL."""
    sv_path = os.path.join(directory, "foods_sprak1.json")
    en_path = os.path.join(directory, "foods_sprak2.json")
    nv_path = os.path.join(directory, "naringsvarden.jsonl")
    if not (os.path.exists(sv_path) and os.path.exists(en_path) and os.path.exists(nv_path)):
        print(f"  ! foods_sprak1/2.json or naringsvarden.jsonl not found in {directory}, skipping")
        return []

    sv = json.load(open(sv_path, encoding="utf-8"))["livsmedel"]
    en = json.load(open(en_path, encoding="utf-8"))["livsmedel"]

    # Sweden publishes both Swedish names and, with sprak=2, British English. Both snapshots cover the same 2,606 `nummer`
    # ids, 2,577 of 2,606 names differ, and that id-set equality is asserted
    # here rather than assumed (the join is on nummer).
    en_by_num = {f["nummer"]: f for f in en}
    sv_by_num = {f["nummer"]: f for f in sv}
    if set(en_by_num) != set(sv_by_num):
        print(f"  ! foods_sprak1.json and foods_sprak2.json id sets differ, skipping")
        return []

    nutrients: dict[int, list[dict]] = {}
    if os.path.exists(nv_path):
        with open(nv_path, encoding="utf-8") as fh:
            for line in fh:
                row = json.loads(line)
                nutrients[row["nummer"]] = row.get("naringsvarden", [])
    if set(nutrients) != set(en_by_num):
        print(f"  ! naringsvarden.jsonl does not cover the foods files' ids, skipping")
        return []

    dropped_basis = 0
    foods: list[Food] = []
    for num, f in en_by_num.items():
        rej.seen += 1
        try:
            fid = int(num)
        except (TypeError, ValueError):
            rej.reject("non-numeric nummer")
            continue
        name = clean_name(f.get("namn"))
        sv_name = clean_name(sv_by_num[num].get("namn"))
        if not name:
            rej.reject("missing or unusable name")
            continue

        v: dict[str, float | None] = {field: None for field in LIVSMEDEL_COLUMNS.values()}
        for n in nutrients[num]:
            code = n.get("euroFIRkod")
            field = LIVSMEDEL_COLUMNS.get(code)
            if field is None:
                continue
            # ENERC appears twice per food, once in kJ and once in kcal; the
            # kcal record is read (2,606 kcal records, all on the W basis, and
            # they agree with kJ/4.184 to +/-1 kcal). The kJ sibling is
            # skipped, so a reissue that drops the kcal record rejects the food
            # loudly ("no energy value") instead of silently converting.
            if code == "ENERC" and n.get("enhet") != "kcal":
                continue
            raw = str(n.get("varde")) if n.get("varde") is not None else None
            value = _bedca_value(raw, n.get("enhet") or "", n.get("matrisenhetkod") or "", code)
            if value is None and to_float(raw) is not None:
                dropped_basis += 1  # a measured value lost to a non-W basis
            if value is not None:
                v[field] = value
        reason = passes_quality(v["kcal"], v["protein"], v["fat"], v["carbs"])
        if reason:
            rej.reject(reason)
            continue
        foods.append(
            Food(
                id=LIVSMEDEL_ID_BASE + fid, name=name, brand=None, barcode=None, lang="en",
                kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # euroFIR CHO = "Carbohydrates, available"; mass closure confirms fibre sits
                # outside it on 2,606 rows.
                carbs_convention="available",
                fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],
                sodium=v["sodium"],
                source="livsmedel",
                aliases=[("sv", sv_name)] if sv_name and sv_name != name else [],
            )
        )
    if dropped_basis:
        print(f"      livsmedel: {dropped_basis} mapped values dropped for an "
              f"off-W basis (incl. null matrisenhetkod)")
    return foods


# ---------------------------------------------------------------------------
# Finland: Fineli (fineli.fi, release 20)
# ---------------------------------------------------------------------------

# EUFDNAME -> (schema field, the COMPUNIT the snapshot's component.csv pins it
# to). The value column is BESTLOC -- the third column of component_value.csv,
# under a name that does not read like one -- and the decimals are commas
# (`1698,30`), which to_float() already handles. ENERC is **kJ only**: no kcal
# code exists in the file, so the kJ-to-kcal path is mandatory, not optional.
FINELI_COMPONENTS: dict[str, tuple[str, str]] = {
    "ENERC": ("kcal", "KJ"),
    "PROT": ("protein", "G"),
    "FAT": ("fat", "G"),
    "CHOAVL": ("carbs", "G"),  # available carbohydrates -- the source's carb value
    "FIBC": ("fiber", "G"),
    "SUGAR": ("sugar", "G"),
    "FASAT": ("sat_fat", "G"),
    "NA": ("sodium", "MG"),
}


_FINELI_WORD_RE = re.compile(r"[A-Za-zÀ-ɏ][A-Za-zÀ-ɏ'’-]*")

# Words that keep a capital when a Fineli name is sentence-cased, keyed by their
# lowercase form so the lookup is exact. Nothing here changes spelling -- only
# case -- and the list is short: an entry that is wrong capitalises
# one word oddly, but a missing entry only leaves a proper noun lowercase, so the
# conservative direction is to omit anything arguable.
#
# Built from evidence rather than intuition: each word below is one the sources
# already in the build (usda, ciqual, bls, bedca, cofid and the other three Nordic
# tables) capitalise mid-name at least three times and never write lowercase.
# Deliberately NOT included, because those same sources write them lowercase at
# least as often and English usage has genuinely moved: the cheeses (cheddar,
# feta, brie, gouda, edam, camembert, mozzarella, parmesan, halloumi), the sauces
# (hollandaise, bearnaise, carbonara, bolognese) and the sausages (bologna,
# frankfurter).
_FINELI_PROPER: dict[str, str] = {
    # Demonyms and place adjectives, which English capitalises without exception.
    "french": "French", "greek": "Greek", "turkish": "Turkish",
    "italian": "Italian", "chinese": "Chinese", "finnish": "Finnish",
    "swiss": "Swiss", "indian": "Indian", "thai": "Thai",
    "japanese": "Japanese", "karelian": "Karelian",
    # Personal names inside dish names.
    "jansson's": "Jansson's", "john's": "John's", "runeberg's": "Runeberg's",
    "lindstrom's": "Lindstrom's", "lindström's": "Lindström's",
    # Brands. Fineli names a manufacturer where the product is only sold under
    # it; the pipeline stores these as generic rows, so the brand lives in the
    # name and reads as a typo in lowercase.
    "kellogg": "Kellogg", "kellogg's": "Kellogg's", "all-bran": "All-Bran",
    "becel": "Becel", "flora": "Flora", "nestle": "Nestle",
    "weetabix": "Weetabix", "semper": "Semper", "cheerios": "Cheerios",
    "quaker": "Quaker", "whopper": "Whopper", "powerade": "Powerade",
    "gatorade": "Gatorade", "schär": "Schär", "twix": "Twix",
    "snickers": "Snickers", "marie": "Marie", "mac": "Mac", "m's": "M's",
    "uncle": "Uncle",
}

# "VITAMIN C" sentence-cases to "vitamin c", which reads as a typo on 141 rows.
# The designator is a letter, not a word, so it is restored by shape rather than
# by vocabulary -- and only A-E and K, so "vitamin s" cannot be invented.
_FINELI_VITAMIN_RE = re.compile(r"\b(vitamin )([a-ek])\b")


def _fineli_case(raw: str) -> str:
    """Sentence-cases a Fineli name, which the open-data dump publishes in
    capitals.

    All 4,238 English names, and all 4,238 Finnish and Swedish ones, arrive
    fully uppercase -- Fineli's own site renders the same rows in normal case
    ("Milk, 3.5 % fat" where the CSV says "MILK, 3.5 % FAT"), so the capitals are
    an export artifact rather than how the publisher presents the data. This is
    the only place the pipeline touches a publisher's capitalisation, and it is
    repairing that artifact, not imposing a style.

    Sentence case matches the rest of the database: usda 74%, ciqual 90%,
    bls 94%, bedca 86%, cofid 90%, matvaretabellen 80%, livsmedel 86% and
    frida 86% of names are first-word-capital-only, and none of the eight
    exceeds 3% title case. Title case would put "Chocolate Confection Filled
    With Marmalade" beside "Apples, raw, without skin" in the same result list.

    The uppercase export destroyed the publisher's own capitalisation and no rule
    can recover it, so proper nouns come from _FINELI_PROPER -- a short, explicit
    list -- and everything else stays lowercase. That is the same direction the
    rest of the pipeline takes with unrecoverable information: drop rather than
    guess. A cleverer name rule was measured at a 44.7% false-positive rate.

    Only fully-uppercase input is transformed -- a mixed-case name is the
    publisher writing something deliberately ("pH") and passes through untouched.
    """
    if not raw or not raw.isupper():
        return raw
    text = _FINELI_WORD_RE.sub(
        lambda m: _FINELI_PROPER.get(m.group(0).lower(), m.group(0).lower()), raw
    )
    text = _FINELI_VITAMIN_RE.sub(lambda m: m.group(1) + m.group(2).upper(), text)
    # Capitalise the first letter, wherever it falls: "3.5 % MILK" starts with a
    # digit, and str.capitalize() would leave the rest of the string lowercased
    # anyway, undoing the proper nouns above.
    for i, ch in enumerate(text):
        if ch.isalpha():
            return text[:i] + ch.upper() + text[i + 1:]
    return text


def load_fineli(directory: str, rej: Rejections) -> list[Food]:
    """Loads the open-data CSV release. cp1252, not UTF-8, and semicolon-
    delimited -- the two things that fail a loader written by habit."""
    food_path = os.path.join(directory, "food.csv")
    if not os.path.exists(food_path):
        print(f"  ! food.csv not found in {directory}, skipping")
        return []

    def rows(name: str) -> list[dict[str, str]]:
        with open(os.path.join(directory, name), encoding="cp1252", newline="") as fh:
            return list(csv.DictReader(fh, delimiter=";"))

    # Names come from the dedicated name files. food.csv's own FOODNAME column
    # is **Finnish** and identical to foodname_FI.csv on all 4,238 rows -- it
    # is redundant, and mistaking it for an English name would store Finnish
    # in foods.name.
    en_names = {r["FOODID"]: r["FOODNAME"] for r in rows("foodname_EN.csv")}
    fi_names = {r["FOODID"]: r["FOODNAME"] for r in rows("foodname_FI.csv")}
    sv_names = {r["FOODID"]: r["FOODNAME"] for r in rows("foodname_SV.csv")}
    food_ids = {r["FOODID"] for r in rows("food.csv")}
    if not (set(en_names) == set(fi_names) == set(sv_names) == food_ids):
        print(f"  ! Fineli food.csv and name files do not carry the same FOODIDs, skipping")
        return []

    # component.csv carries the unit per component; the loader verifies it
    # rather than trusting it, because a reissue that silently moved a column
    # to kJ would store energy 4.184x out and only the quiet half of the rows
    # would trip the 0-900 gate.
    unit_by_component = {r["EUFDNAME"]: r["COMPUNIT"] for r in rows("component.csv")}
    for code, (_field, unit) in FINELI_COMPONENTS.items():
        if unit_by_component.get(code) != unit:
            print(f"  ! Fineli component {code} is {unit_by_component.get(code)!r}, "
                  f"expected {unit!r}; refusing to guess")
            return []

    values: dict[str, dict[str, float | None]] = {}
    for r in rows("component_value.csv"):
        code = r["EUFDNAME"]
        entry = FINELI_COMPONENTS.get(code)
        if entry is None:
            continue
        field, _unit = entry
        v = to_float(r["BESTLOC"])
        if v is None:
            continue
        if code == "ENERC":
            v = v / 4.184  # kJ -> kcal, the same arithmetic BEDCA's kJ path uses
        elif code == "NA":
            v = v / 1000.0  # mg/100g -> g, like every sibling source
        values.setdefault(r["FOODID"], {})[field] = v

    foods: list[Food] = []
    for fid, name_raw in sorted(en_names.items(), key=lambda kv: int(kv[0])):
        rej.seen += 1
        name = clean_name(_fineli_case(name_raw))
        if not name:
            rej.reject("missing or unusable name")
            continue
        try:
            food_id = int(fid)
        except ValueError:
            rej.reject("non-numeric FOODID")
            continue
        v = values.get(fid, {})
        reason = passes_quality(v.get("kcal"), v.get("protein"), v.get("fat"), v.get("carbs"))
        if reason:
            rej.reject(reason)
            continue
        fi_name = clean_name(_fineli_case(fi_names[fid]))
        sv_name = clean_name(_fineli_case(sv_names[fid]))
        # Attached only to rows that survived the quality filter. Only 4 of
        # 4,238 Finnish names read like the English one, so nearly every row
        # earns a fi alias. The Swedish alias is attached too -- Fineli's
        # foods are Finnish products a Swedish user still searches for, and
        # the UNIQUE(food_id, lang) index permits both languages on one food.
        # Whether that is worth it is measured and reported in the findings.
        aliases = []
        if fi_name and fi_name != name:
            aliases.append(("fi", fi_name))
        if sv_name and sv_name != name:
            aliases.append(("sv", sv_name))
        foods.append(
            Food(
                id=FINELI_ID_BASE + food_id, name=name, brand=None, barcode=None, lang="en",
                kcal=v.get("kcal"), protein=v.get("protein"), fat=v.get("fat"),
                carbs=v.get("carbs"),
                # CHOAVL is the only carbohydrate component the file carries -- available
                # carbohydrate by name, and no CHOCDF (by-difference) sibling exists to
                # confuse it with.
                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"), sat_fat=v.get("sat_fat"),
                sodium=v.get("sodium"),
                source="fineli",
                aliases=aliases,
            )
        )
    return foods


# ---------------------------------------------------------------------------
# Denmark: Frida (fcdb.fooddata.dk, version 6.1)
# ---------------------------------------------------------------------------

# Column -> schema field, from the CSV written by tools/extract_frida_csv.py,
# which pins the Parameter sheet rows against the workbook before emitting them
# -- the parameter trap is the real risk of this source: Frida publishes
# analytical and labelling variants side by side (4 energy parameters, 3
# protein, 3 carbohydrate, 4 sugar), and picking the wrong one is silently
# wrong for every row. The extractor pins, the loader trusts, exactly as
# load_bls trusts extract_bls_csv.py. KCAL is ParameterID 356 'Energy (kcal)',
# 'kcal/100 g'; CARBS is 172 'Available carbohydrates' (see the extractor's
# table for the decoys and the Parameter sheet's Unit column for each).
FRIDA_COLUMNS = {
    "KCAL": "kcal",
    "PROTEIN": "protein",
    "FAT": "fat",
    # ParameterID 248 "Sum saturated fatty acids", g/100g on 1,388 of the 1,390
    # foods. It sits in Frida's "Fatty acids sums" group rather than among the
    # proximates, which is how the first pass of this loader came to hardcode
    # sat_fat=None on the belief that Frida published no total.
    "SAT_FAT": "sat_fat",
    "CARBS": "carbs",
    "FIBRE": "fiber",
    "SUGAR": "sugar",
    "SODIUM": "sodium",
}


def load_frida(path: str, rej: Rejections) -> list[Food]:
    """Loads the CSV written by tools/extract_frida_csv.py."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    foods: list[Food] = []
    with open(path, encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh):
            rej.seen += 1
            name = clean_name(r["FoodName"])
            da_name = clean_name(r["FodevareNavn"])
            if not name:
                rej.reject("missing or unusable name")
                continue
            try:
                fid = int(r["FoodID"])
            except ValueError:
                rej.reject("non-numeric FoodID")
                continue
            v = {field: to_float(r[col]) for col, field in FRIDA_COLUMNS.items()}
            reason = passes_quality(v["kcal"], v["protein"], v["fat"], v["carbs"])
            if reason:
                rej.reject(reason)
                continue
            foods.append(
                Food(
                    id=FRIDA_ID_BASE + fid, name=name, brand=None, barcode=None, lang="en",
                    kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                    # ParameterID 172 "Available carbohydrates" (the loader stores 172, not the
                    # by-difference sibling 170); 170 - (172 + fibre) = 0.000 on all 1,389 foods
                    #.
                    carbs_convention="available",
                    fiber=v["fiber"], sugar=v["sugar"],
                    sat_fat=v["sat_fat"],
                    # ParameterID 201 'Sodium' is mg/100g (the Parameter
                    # sheet's own Unit column), so divide by 1000 exactly as
                    # every sibling source does. 327 'Salt labelling' is the
                    # decoy: g/100g, and salt is not sodium.
                    sodium=(v["sodium"] / 1000.0) if v["sodium"] is not None else None,
                    source="frida",
                    aliases=[("da", da_name)] if da_name and da_name != name else [],
                )
            )
    return foods


# ---------------------------------------------------------------------------
# Swiss FCDB (Schweizer Nahrwertdatenbank)
# ---------------------------------------------------------------------------

# `ID` is numeric and unique (1..14,304 across the 1,216 foods of the 2026
# snapshot). The publisher documents no upper bound on the code, so the
# ceiling is the band itself, as for the Nordic plain-integer keys: base + key stays inside [160e9, 170e9) for any key under 1e10, which
# the table would have to grow by a factor of ~700,000 to outgrow. The band
# sits in the free ground between Frida's 150e9 and TCA's 170e9, and 1.6e11
# clears BEDCA's 1e11 + 2713 ceiling by 59,999,997,287.
SWISS_ID_BASE = 160_000_000_000

# Output column of tools/extract_swiss_csv.py -> schema field. The extractor
# verifies each column's header text against the workbook before emitting it,
# so the loader trusts that verification the way load_bls trusts
# extract_bls_csv.py. The kJ and `Salt (NaCl) (g)` columns are not
# in the CSV -- kcal and `Sodium (Na) (mg)` are the mapped pair, and the
# extractor pins the decoys so a reissue cannot swap them in silently.
SWISS_COLUMNS = {
    "KCAL": "kcal",
    "FAT": "fat",
    "SAT_FAT": "sat_fat",
    "CARBS": "carbs",
    "SUGAR": "sugar",
    "FIBER": "fiber",
    "PROTEIN": "protein",
    "SODIUM": "sodium",
}


def _swiss_value(raw: str | None) -> float | None:
    """Parses a Swiss value cell.

    The Swiss vocabulary is its own: 'tr.' means traces (present, amount
    unknown; the project convention maps it to 0.0), 'n.d.' means not
    determined and maps to None, and '<0.5' means below the detection limit,
    which to_float() halves -- the same call every sibling loader makes. The
    four language editions write the markers in their own words ('Sp.'/'k.A.'/
    'n.i.'/'nd'); the extractor verifies they agree and emits the English file,
    so this parser sees only the English vocabulary.
    """
    if raw is None:
        return None
    s = raw.strip()
    if s in ("", "n.d."):
        return None
    if s == "tr.":
        return 0.0
    return to_float(s)


def load_swiss(path: str, rej: Rejections) -> list[Food]:
    """Loads the joined CSV written by tools/extract_swiss_csv.py."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    # attached / loanword / unusable, counted per language so the three alias
    # counts are reported separately rather than as one number that hides a
    # language the join dropped.
    alias_counts: dict[str, list[int]] = {lang: [0, 0, 0] for lang in ("de", "fr", "it")}

    foods: list[Food] = []
    for r in rows:
        rej.seen += 1
        name = clean_name(r["NAME"])
        if not name:
            rej.reject("missing or unusable name")
            continue
        try:
            fid = int((r["ID"] or "").strip())
        except ValueError:
            rej.reject("non-numeric ID")
            continue

        v = {key: _swiss_value(r[col]) for col, key in SWISS_COLUMNS.items()}
        reason = passes_quality(v["kcal"], v["protein"], v["fat"], v["carbs"])
        if reason:
            rej.reject(reason)
            continue

        # Three aliases on one food is permitted by the schema's UNIQUE(food_id,
        # lang) index since the languages differ. Each language whose name reads
        # the same as the stored English name is skipped -- the loanword case
        # (Tofu, Gin, Gazpacho), the same skip Ciqual/BLS/BEDCA make. The four
        # exports are verified by the extractor to carry the same ID set, so a
        # name present in one language cannot be silently absent from the join.
        langs: list[tuple[str, str]] = []
        for lang, col in (("de", "NAME_DE"), ("fr", "NAME_FR"), ("it", "NAME_IT")):
            native = clean_name(r[col])
            if native is None:
                alias_counts[lang][2] += 1
            elif native == name:
                alias_counts[lang][1] += 1
            else:
                alias_counts[lang][0] += 1
                langs.append((lang, native))

        foods.append(
            Food(
                id=SWISS_ID_BASE + fid, name=name, brand=None, barcode=None,
                lang="en",
                kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # Col 42 "Carbohydrates, available (g)"; kcal = 4P+9F+4C+2*FIBT confirms
                # fibre is paid separately, median 0.000 over 1,127 rows.
                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),
                # `Sodium (Na) (mg)` is milligrams, like every other source's
                # column; stored grams, so divide by 1000 exactly as the
                # siblings do. `Salt (NaCl) (g)` is the decoy and never reaches
                # this loader -- the extractor does not emit it.
                sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                source="swiss",
                aliases=langs,
            )
        )
    for lang in ("de", "fr", "it"):
        a, loan, unusable = alias_counts[lang]
        print(f"      aliases: {lang} {a} attached ({loan} loanword, {unusable} unusable)")
    return foods


# ---------------------------------------------------------------------------
# Portuguese TCA (Tabela da Composicao de Alimentos, INSA)
# ---------------------------------------------------------------------------

# `Cod` is numeric and unique (1..2,122,000,015 across the 1,376 foods of the
# v7.1 snapshot). No published bound on the code, so the honest ceiling is the
# band itself: base + key stays inside [170e9, 180e9) for any key under 1e10,
# which the table would have to grow by a factor of ~4.7 million to outgrow.
TCA_ID_BASE = 170_000_000_000

# Output column of tools/extract_tca_csv.py -> schema field. The extractor
# verifies each column's header text and the component-correspondence sheet
# before emitting it, the same trust load_bls puts in extract_bls_csv.py. The
# kJ and `Sal` columns are pinned decoys and never reach this loader.
TCA_COLUMNS = {
    "KCAL": "kcal",
    "FAT": "fat",
    "SAT_FAT": "sat_fat",
    "CARBS": "carbs",
    "SUGAR": "sugar",
    "FIBER": "fiber",
    "PROTEIN": "protein",
    "SODIUM": "sodium",
}

# TCA publishes Portuguese names only, so the stored name is Portuguese and `foods.lang` is 'pt'.
# The language promotion in the search is bounded to rows that lead with the query, so a 'pt' row
# does not outrank better English matches. No alias: it would equal the name.
#
# pt-BR and pt-PT are one language here. There is no buildable Brazilian table, and European
# Portuguese shares most everyday food words. Known false friend: TCA's `presunto` is cured ham.
TCA_NAME_LANG = "pt"

# The data sheet's banner declares every value per 100 g of edible portion
# EXCEPT this group, whose values are per 100 ml. The schema stores per 100 g
# and the workbook carries no density for the conversion, so these rows are
# rejected whole rather than stored on a basis the row itself contradicts --
# the same rule BEDCA applies per value. 36 rows in the snapshot; 33 of them
# would fail the Atwater gate anyway, the real cost is three beers.
TCA_ALCOHOL_GROUP = "Bebidas alcoólicas"


def load_tca(path: str, rej: Rejections) -> list[Food]:
    """Loads the CSV written by tools/extract_tca_csv.py."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    foods: list[Food] = []
    for r in rows:
        rej.seen += 1
        if (r["NIVEL1"] or "").strip() == TCA_ALCOHOL_GROUP:
            # Per-100-ml rows, see TCA_ALCOHOL_GROUP above. Counted, not
            # guessed: converting needs a density the workbook does not carry.
            rej.reject("per 100 ml basis (alcoholic beverages group)")
            continue
        name = clean_name(r["NOME"])
        if not name:
            rej.reject("missing or unusable name")
            continue
        try:
            fid = int((r["COD"] or "").strip())
        except ValueError:
            rej.reject("non-numeric Cod")
            continue

        v = {key: to_float(r[col]) for col, key in TCA_COLUMNS.items()}
        reason = passes_quality(v["kcal"], v["protein"], v["fat"], v["carbs"])
        if reason:
            rej.reject(reason)
            continue

        foods.append(
            Food(
                id=TCA_ID_BASE + fid, name=name, brand=None, barcode=None,
                lang=TCA_NAME_LANG,
                kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # Col 14 "Hidratos de carbono" = sugars+oligo+starch exactly on 1,369 rows
                # -- available carbohydrate.
                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),
                # `Sódio` is mg/100 g, like every other source's column; stored
                # grams, so divide by 1000. `Sal` (g) is the decoy and never
                # reaches this loader -- the extractor does not emit it.
                sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                source="tca",
                # No alias in either storage option, see TCA_NAME_LANG.
                aliases=[],
            )
        )
    return foods


# ---------------------------------------------------------------------------
# Canadian Nutrient File (Health Canada)
# ---------------------------------------------------------------------------

# `Food_Code` is a 1-4 digit integer (measured 2026-08-23: all 5,993 codes are
# digits at most 4 wide, max observed 8011, all distinct), so ids are
# CNF_ID_BASE + int(code). The theoretical ceiling is the code *space*, 9999, not
# the observed maximum, so the band is [180_000_000_000, 180_000_010_000):
# above CoFID's theoretical ceiling (110_001_000_000) by ~70e9, clear of the
# Nordic, Swiss and TCA bands at 1.2e11-1.8e11, and below Open Food Facts'
# 1e12 by ~820e9.
CNF_ID_BASE = 180_000_000_000

# CNF's nutrient ids are Health Canada's own numbering, NOT FoodData Central's.
# The USDA ids (1008, 1003, 1004, 1005, 1079, 1093, 2000) do not even exist as
# CNF codes, so copying USDA_NUTRIENTS would silently map nothing and every row
# would be rejected "no energy value" while every count still looked healthy.
# Verified against the archive's own Nutrient_Name.csv and the 2026 users guide
# (Appendix B: List nutrients) -- both agree, and the guide's per-nutrient food
# counts reconcile exactly with the file (TSUG 83.91% = 5,029 rows, TDF 96.35%
# = 5,774, NA 99.28% = 5,950, TSAT 96.23% = 5,767).
#
# Energy: 208 KCAL (kilocalorie) is read; 268 KJ exists for every food and is
# not. Both columns are published rounded to whole units (the guide lists 0
# decimal places for each), so 151 of 5,980 rows disagree with kJ/4.184 by at
# most 1.2 kcal and one row (Peanut butter, smooth type) by 8.75 -- the
# declared kcal is authoritative, the same convention Norway's loader settled
# on. Carbohydrate is total "by difference" (fibre included), the USDA
# convention the pipeline already holds.
CNF_NUTRIENTS = {
    "208": "kcal", "203": "protein", "204": "fat", "205": "carbs",
    "291": "fiber", "269": "sugar", "606": "sat_fat", "307": "sodium",
}


def load_cnf(path: str, rej: Rejections) -> list[Food]:
    """Loads the Canadian Nutrient File zip (relational CSVs, USDA-style)."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with zipfile.ZipFile(path) as z:
        # Fails loudly on a renamed member, unlike load_usda's silent KeyError
        # pass: the CNF layout is the riskiest part of this loader, so a reissue that renames Food_Name.csv must not
        # degrade to a plausible-looking "kept 0".
        missing = [n for n in ("Food_Name.csv", "Nutrient_Amount.csv")
                   if n not in z.namelist()]
        if missing:
            print(f"  ! {path} is missing {missing}, skipping", file=sys.stderr)
            return []

        def rows(name: str):
            with z.open(name) as fh:
                yield from csv.DictReader(
                    io.TextIOWrapper(fh, "utf-8-sig", errors="replace")
                )

        names: dict[str, tuple[str | None, str | None]] = {}
        for r in rows("Food_Name.csv"):
            names[r["Food_Code"]] = (
                r.get("Food_Description_EN"), r.get("Food_Description_FR")
            )

        values: dict[str, dict[str, float]] = {}
        for r in rows("Nutrient_Amount.csv"):
            key = CNF_NUTRIENTS.get(r.get("Nutrient_Code", ""))
            if not key:
                continue
            v = to_float(r.get("Nutrient_Amount"))
            if v is not None:
                values.setdefault(r["Food_Code"], {})[key] = v

    foods: list[Food] = []
    for code, (name_raw, fr_raw) in names.items():
        rej.seen += 1
        name = clean_name(name_raw)
        if not name:
            # 5 rows in the 2026 snapshot, all over the 120-char cap. Counted
            # rather than silently dropped, because the name cap is the known trap
            # for this source.
            rej.reject("missing or unusable name")
            continue
        fr = clean_name(fr_raw)
        v = values.get(code, {})
        reason = passes_quality(
            v.get("kcal"), v.get("protein"), v.get("fat"), v.get("carbs")
        )
        if reason:
            rej.reject(reason)
            continue
        try:
            fid = CNF_ID_BASE + int(code)
        except ValueError:
            rej.reject("non-numeric food code")
            continue
        foods.append(
            Food(
                id=fid, name=name, brand=None, barcode=None, lang="en",
                kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # Code 205 "Carbohydrate, total (by difference)".
                carbs_convention="by_difference",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),
                # Sodium is milligrams in CNF, grams elsewhere.
                sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                source="cnf",
                # The French column is a Canadian French name, attached exactly
                # as Ciqual's fr alias is -- a Canadian French name and a Ciqual
                # French name are aliases on *different* foods, so UNIQUE
                # (food_id, lang) is not in play here -- and skipped when it
                # reads the same as the English one (9 rows in the snapshot).
                aliases=[("fr", fr)] if fr and fr != name else [],
            )
        )
    return foods


# ---------------------------------------------------------------------------
# Australian AFCD (FSANZ)
# ---------------------------------------------------------------------------

# `Public Food Key` is F followed by 6 digits (measured 2026-08-23: all 1,588
# keys match ^F\d{6}$, offsets 2..11006, all distinct), so ids are
# AFCD_ID_BASE + int(key[1:]). The theoretical ceiling is the key *space*,
# 999_999, not the observed maximum, so the band is [190_000_000_000,
# 190_001_000_000): above CNF's ceiling by ~10e9, clear of the Nordic, Swiss and TCA bands,
# and below Open Food Facts' 1e12 by ~810e9.
AFCD_ID_BASE = 190_000_000_000

# Column -> schema field. The CSV is written
# by tools/extract_afcd_csv.py, which verifies these columns against the
# workbook's header rows before emitting them; the loader trusts that
# verification the way load_bls trusts extract_bls_csv.py. Energy is read
# from E_KJ separately, below.
#
# The workbook carries no total-carbohydrate column; AVAIL_CHO is its
# carbohydrate concept. Measured: energy-with-fibre in kJ reproduces
# 4 kcal/g protein + 9 fat + 4 available carbohydrate + 7 alcohol + 2 fibre
# on all 1,588 rows within the pipeline gate's tolerance, so those two columns
# are one consistent system and the energy column that goes with them is the
# "with dietary fibre" one. SATFAT is the per-100g-food total (g), not the
# per-100g-fatty-acid (%T) sibling. Sodium is milligrams, like every other
# source's column.
AFCD_COLUMNS = {
    "PROTEIN": "protein",
    "FAT": "fat",
    "AVAIL_CHO": "carbs",
    "FIBRE": "fiber",
    "SUGAR": "sugar",
    "SATFAT": "sat_fat",
    "SODIUM": "sodium",
}


def load_afcd(path: str, rej: Rejections) -> list[Food]:
    """Loads the CSV written by tools/extract_afcd_csv.py."""
    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    foods: list[Food] = []
    for r in rows:
        rej.seen += 1
        name = clean_name(r["Food Name"])
        if not name:
            rej.reject("missing or unusable name")
            continue
        m = re.fullmatch(r"F(\d{6})", (r["Public Food Key"] or "").strip())
        if not m:
            rej.reject("non-standard food key")
            continue
        kj = to_float(r["E_KJ"])
        v = {field: to_float(r[col]) for col, field in AFCD_COLUMNS.items()}
        # The workbook publishes energy in kJ only. The energy column that
        # matches the nutrient system is "with dietary fibre" (see the
        # AFCD_COLUMNS comment); it is converted here, in the loader, so the
        # extractor stays faithful to the published numbers.
        kcal = kj / 4.184 if kj is not None else None
        reason = passes_quality(
            kcal, v["protein"], v["fat"], v["carbs"]
        )
        if reason:
            rej.reject(reason)
            continue
        foods.append(
            Food(
                id=AFCD_ID_BASE + int(m.group(1)),
                name=name, brand=None, barcode=None, lang="en",
                kcal=kcal, protein=v["protein"], fat=v["fat"], carbs=v["carbs"],
                # AVAIL_CHO: "Available carbohydrate, without sugar alcohols" (col 39);
                # both energy columns reconcile with carbs at 4 and fibre at 2.
                carbs_convention="available",
                fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],
                # Sodium is milligrams in AFCD, grams elsewhere.
                sodium=(v["sodium"] / 1000.0) if v["sodium"] is not None else None,
                source="afcd",
                # English names only, so there is no language alias to attach;
                # main()'s en-GB vocabulary pass covers the rest.
                aliases=[],
            )
        )
    return foods


# ---------------------------------------------------------------------------
# Country vocabulary
# ---------------------------------------------------------------------------
# Open Food Facts countries_tags values are `en:<slug>` tags, and the slug is
# derived from the English main name: "France" -> en:france, "Cote d'Ivoire" ->
# en:cote-d-ivoire, "United Kingdom" -> en:united-kingdom. This table maps that
# slug to the taxonomy's own 2-letter code, parsed from OFF's countries
# taxonomy (openfoodfacts-server/taxonomies/countries.txt) on 2026-08-22.
# Two of those codes are not ISO 3166-1: the taxonomy overrides GB
# with UK, and "world" is a pseudo-entry. UK is stored as-is -- it is the code
# the taxonomy itself uses, and what a preference will compare against; "world"
# is kept in the table only so the parser can recognise it and collapse it to
# NULL rather than count it as an unresolved fragment.
TAG_TO_ISO: dict[str, str] = {
    'afghanistan': 'AF', 'aland-islands': 'AX', 'albania': 'AL',
    'algeria': 'DZ', 'american-samoa': 'AS', 'andorra': 'AD',
    'angola': 'AO', 'anguilla': 'AI', 'antarctic': 'AQ',
    'antigua-and-barbuda': 'AG', 'argentina': 'AR', 'armenia': 'AM',
    'aruba': 'AW', 'australia': 'AU', 'austria': 'AT',
    'azerbaijan': 'AZ', 'bahrain': 'BH', 'bangladesh': 'BD',
    'barbados': 'BB', 'belarus': 'BY', 'belgium': 'BE',
    'belize': 'BZ', 'benin': 'BJ', 'bermuda': 'BM',
    'bhutan': 'BT', 'bolivia': 'BO', 'bosnia-and-herzegovina': 'BA',
    'botswana': 'BW', 'bouvet-island': 'BV', 'brazil': 'BR',
    'british-indian-ocean-territory': 'IO', 'british-virgin-islands': 'VG', 'brunei': 'BN',
    'bulgaria': 'BG', 'burkina-faso': 'BF', 'burundi': 'BI',
    'cambodia': 'KH', 'cameroon': 'CM', 'canada': 'CA',
    'cape-verde': 'CV', 'caribbean-netherlands': 'BQ', 'cayman-islands': 'KY',
    'central-african-republic': 'CF', 'chad': 'TD', 'chile': 'CL',
    'china': 'CN', 'christmas-island': 'CX', 'cocos-keeling-islands': 'CC',
    'colombia': 'CO', 'comoros': 'KM', 'cook-islands': 'CK',
    'costa-rica': 'CR', 'cote-d-ivoire': 'CI', 'croatia': 'HR',
    'cuba': 'CU', 'curacao': 'CW', 'cyprus': 'CY',
    'czech-republic': 'CZ', 'democratic-republic-of-the-congo': 'CD', 'denmark': 'DK',
    'djibouti': 'DJ', 'dominica': 'DM', 'dominican-republic': 'DO',
    'ecuador': 'EC', 'egypt': 'EG', 'el-salvador': 'SV',
    'equatorial-guinea': 'GQ', 'eritrea': 'ER', 'estonia': 'EE',
    'ethiopia': 'ET', 'falkland-islands': 'FK', 'faroe-islands': 'FO',
    'federated-states-of-micronesia': 'FM', 'fiji': 'FJ', 'finland': 'FI',
    'france': 'FR', 'french-guiana': 'GF', 'french-polynesia': 'PF',
    'french-southern-and-antarctic-lands': 'TF', 'gabon': 'GA', 'gambia': 'GM',
    'georgia': 'GE', 'germany': 'DE', 'ghana': 'GH',
    'gibraltar': 'GI', 'greece': 'GR', 'greenland': 'GL',
    'grenada': 'GD', 'guadeloupe': 'GP', 'guam': 'GU',
    'guatemala': 'GT', 'guernsey': 'GG', 'guinea': 'GN',
    'guinea-bissau': 'GW', 'guyana': 'GY', 'haiti': 'HT',
    'heard-island-and-mcdonald-islands': 'HM', 'honduras': 'HN', 'hong-kong': 'HK',
    'hungary': 'HU', 'iceland': 'IS', 'india': 'IN',
    'indonesia': 'ID', 'iran': 'IR', 'iraq': 'IQ',
    'ireland': 'IE', 'isle-of-man': 'IM', 'israel': 'IL',
    'italy': 'IT', 'jamaica': 'JM', 'japan': 'JP',
    'jersey': 'JE', 'jordan': 'JO', 'kazakhstan': 'KZ',
    'kenya': 'KE', 'kiribati': 'KI', 'kosovo': 'XK',
    'kuwait': 'KW', 'kyrgyzstan': 'KG', 'laos': 'LA',
    'latvia': 'LV', 'lebanon': 'LB', 'lesotho': 'LS',
    'liberia': 'LR', 'libya': 'LY', 'liechtenstein': 'LI',
    'lithuania': 'LT', 'luxembourg': 'LU', 'macau': 'MO',
    'madagascar': 'MG', 'malawi': 'MW', 'malaysia': 'MY',
    'maldives': 'MV', 'mali': 'ML', 'malta': 'MT',
    'marshall-islands': 'MH', 'martinique': 'MQ', 'mauritania': 'MR',
    'mauritius': 'MU', 'mayotte': 'YT', 'mexico': 'MX',
    'moldova': 'MD', 'monaco': 'MC', 'mongolia': 'MN',
    'montenegro': 'ME', 'montserrat': 'MS', 'morocco': 'MA',
    'mozambique': 'MZ', 'myanmar': 'MM', 'namibia': 'NA',
    'nauru': 'NR', 'nepal': 'NP', 'netherlands': 'NL',
    'new-caledonia': 'NC', 'new-zealand': 'NZ', 'nicaragua': 'NI',
    'niger': 'NE', 'nigeria': 'NG', 'niue': 'NU',
    'norfolk-island': 'NF', 'north-korea': 'KP', 'north-macedonia': 'MK',
    'northern-mariana-islands': 'MP', 'norway': 'NO', 'oman': 'OM',
    'pakistan': 'PK', 'palau': 'PW', 'panama': 'PA',
    'papua-new-guinea': 'PG', 'paraguay': 'PY', 'peru': 'PE',
    'philippines': 'PH', 'pitcairn': 'PN', 'poland': 'PL',
    'portugal': 'PT', 'puerto-rico': 'PR', 'qatar': 'QA',
    'republic-of-the-congo': 'CG', 'reunion': 'RE', 'romania': 'RO',
    'russia': 'RU', 'rwanda': 'RW', 'saint-barthelemy': 'BL',
    'saint-helena': 'SH', 'saint-kitts-and-nevis': 'KN', 'saint-lucia': 'LC',
    'saint-martin': 'MF', 'saint-pierre-and-miquelon': 'PM', 'saint-vincent-and-the-grenadines': 'VC',
    'samoa': 'WS', 'san-marino': 'SM', 'sao-tome-and-principe': 'ST',
    'saudi-arabia': 'SA', 'senegal': 'SN', 'serbia': 'RS',
    'seychelles': 'SC', 'sierra-leone': 'SL', 'singapore': 'SG',
    'sint-maarten': 'SX', 'slovakia': 'SK', 'slovenia': 'SI',
    'solomon-islands': 'SB', 'somalia': 'SO', 'south-africa': 'ZA',
    'south-georgia-and-the-south-sandwich-islands': 'GS', 'south-korea': 'KR', 'south-sudan': 'SS',
    'spain': 'ES', 'sri-lanka': 'LK', 'state-of-palestine': 'PS',
    'sudan': 'SD', 'suriname': 'SR', 'svalbard-and-jan-mayen': 'SJ',
    'swaziland': 'SZ', 'sweden': 'SE', 'switzerland': 'CH',
    'syria': 'SY', 'taiwan': 'TW', 'tajikistan': 'TJ',
    'tanzania': 'TZ', 'thailand': 'TH', 'the-bahamas': 'BS',
    'timor-leste': 'TL', 'togo': 'TG', 'tokelau': 'TK',
    'tonga': 'TO', 'trinidad-and-tobago': 'TT', 'tunisia': 'TN',
    'turkey': 'TR', 'turkmenistan': 'TM', 'turks-and-caicos-islands': 'TC',
    'tuvalu': 'TV', 'uganda': 'UG', 'ukraine': 'UA',
    'united-arab-emirates': 'AE', 'united-kingdom': 'UK', 'united-states': 'US',
    'united-states-minor-outlying-islands': 'UM', 'uruguay': 'UY', 'uzbekistan': 'UZ',
    'vanuatu': 'VU', 'vatican-city': 'VA', 'venezuela': 'VE',
    'vietnam': 'VN', 'virgin-islands-of-the-united-states': 'VI', 'wallis-and-futuna': 'WF',
    'western-sahara': 'EH', 'world': 'world', 'yemen': 'YE',
    'yugoslavia': 'YU', 'zambia': 'ZM', 'zimbabwe': 'ZW',
}

# The generic tier is one national table per country. A generic row's country
# is its source's country; main() indexes this dict for every generic source,
# and a new national table must declare itself here or the build fails loudly
#.
GENERIC_SOURCE_COUNTRY: dict[str, str] = {
    "usda": "US", "ciqual": "FR", "bls": "DE", "bedca": "ES",
    # The taxonomy's code for the United Kingdom is UK, not ISO's GB: TAG_TO_ISO maps
    # the 'united-kingdom' slug to UK, and the app's SettingsManager translates ISO
    # 'GB' to taxonomy 'UK' (ISO_TO_TAXONOMY_COUNTRY). A 'GB' written here would match
    # nothing on a British phone, silently.
    "cofid": "UK",
    "matvaretabellen": "NO", "livsmedel": "SE", "fineli": "FI", "frida": "DK",
    "swiss": "CH", "tca": "PT",
    "cnf": "CA",
    "afcd": "AU",
    "nevo": "NL",
    "foodfiles": "NZ",
}

# The language a national table's own names are in, for the sources that publish
# a native name alongside an English one, so national tables show the national language.
#
# NOT listed here, deliberately:
#   tca                       already native (TCA_NAME_LANG), the proof this works
#   usda, cnf, afcd, cofid    English-language publishers; there is no native name
#   swiss                     see SWISS_NAME_LANG below, which is a real choice
GENERIC_NATIVE_NAME_LANG: dict[str, str] = {
    "ciqual": "fr", "bls": "de", "bedca": "es",
    "matvaretabellen": "nb", "livsmedel": "sv", "fineli": "fi", "frida": "da",
}

# Switzerland publishes German, French and Italian together and the extractor
# joins all three. German is stored because it is the largest language community
# (~62% of residents); French and Italian stay as aliases, so a Romand or a
# Ticinese reader still gets their own name through the display subquery and the
# food-name priority list. This is the one entry here that is a judgement call
# rather than "the table has one native language", which is why it is named
# separately instead of hiding in the mapping above.
SWISS_NAME_LANG = "de"


# Compound-splitting for the languages that write the basic food as one
# word. FTS5's unicode61 tokenizer plus a prefix query matches only a token that
# STARTS with the term, so `"maelk"*` can never reach `Sødmælk`; and FTS5 has no
# leading wildcard, so no query-side change can fix it. The split happens here and
# its output goes to `foods.search_extra`, which is indexed for MATCHING ONLY.
#
# Germanic and Finnish compounds put the head LAST -- sød+mælk is a milk, vollkorn+
# brot is a bread -- so a lexicon word that is a proper SUFFIX of a token is the head.
#
# The three guards below come from measurement. Ungated, the single most frequently "recovered" German head was `chen`, the
# diminutive ending of Hähnchen, and `rot`/`let`/`itu`/`gurtti` (a fragment of
# jogurtti) came out the same way -- `rot` alone would have matched half the German
# table. Requiring the REMAINDER to be a lexicon word too is what removes them:
# sød+mælk survives because `sød` is a word, hähn+chen does not because `hähn` is not.
COMPOUND_LANGS = {"da", "nb", "sv", "de", "fi"}
# A head shorter than this is usually an ending rather than a word. Measured: 5 was
# too aggressive and cost `brod`, `melk`, `mjolk`, `liha` and `jauho`, taking Danish coverage from 8% of rows to 4%.
COMPOUND_MIN_HEAD = 4
# Times a word must stand alone to enter the lexicon. 3 was too strict for the smaller generic
# tables: BEDCA's own `garbanzo` occurs in only 2 rows, so it never entered BEDCA's vocabulary and
# `garbanzos` could not be depluralised to reach it -- the query fell through to branded results
# instead of BEDCA's own. At 2, the newly admitted words are still real food vocabulary (cebolla,
# avocado, margarine, zwiebel). Fragments are caught by the separate "both halves must be a lexicon
# word" rule in split_compounds()/FtsQuery.splitCompound, not by this threshold. At 1, every token
# would count as a word.
COMPOUND_MIN_COUNT = 2
COMPOUND_LEX_LEN = (3, 12)
# Germanic linking morphemes, so `sød` is still recognised inside `sødmælk` and
# `rinder` inside `rinderbraten`.
COMPOUND_LINKS = ("", "s", "es", "n", "en", "e", "er")

_COMPOUND_TOKEN = re.compile(r"[0-9a-z]+")


LEX_SEP = "\t"


def lexicon_from_names(names: list[str]) -> set[str]:
    """The vocabulary of one language: words of [COMPOUND_LEX_LEN] seen COMPOUND_MIN_COUNT times.

    One definition, three readers -- [split_compounds] on the build side, the
    `compound-lexicon.txt` asset written by [write_compound_lexicon] for the query side, and `tools/export_compound_lexicon.py`, which regenerates that asset from an
    already-built database. Splitting it in two is how the build side and the query side would
    quietly stop agreeing about which words exist.
    """
    lo, hi = COMPOUND_LEX_LEN
    counts: dict[str, int] = {}
    for name in names:
        for token in _COMPOUND_TOKEN.findall(fold_name(name)):
            counts[token] = counts.get(token, 0) + 1
    return {t for t, n in counts.items() if lo <= len(t) <= hi and n >= COMPOUND_MIN_COUNT}


def split_compounds(generic: list[Food]) -> dict[str, tuple[int, int]]:
    """Fills `search_extra` with head words recovered from compounds.

    Returns {lang: (rows_helped, distinct_heads)} for the build report. Operates per
    LANGUAGE, using a lexicon built from that language's own rows -- no downloaded
    word list, and a table's vocabulary is exactly the vocabulary its readers type.
    """
    by_lang: dict[str, list[Food]] = {}
    for f in generic:
        if f.lang in COMPOUND_LANGS:
            by_lang.setdefault(f.lang, []).append(f)

    report: dict[str, tuple[int, int]] = {}
    for lang, rows in sorted(by_lang.items()):
        folded = [_COMPOUND_TOKEN.findall(fold_name(f.name)) for f in rows]
        lexicon = lexicon_from_names([f.name for f in rows])

        helped = 0
        emitted: set[str] = set()
        for f, toks in zip(rows, folded):
            found: set[str] = set()
            for token in toks:
                if len(token) < COMPOUND_MIN_HEAD + 3:
                    continue
                for cut in range(1, len(token) - COMPOUND_MIN_HEAD + 1):
                    head, rest = token[cut:], token[:cut]
                    if head not in lexicon or head == token:
                        continue
                    for link in COMPOUND_LINKS:
                        stem = rest[: -len(link)] if link and rest.endswith(link) else rest
                        if len(stem) >= 3 and stem in lexicon:
                            found.add(head)
                            break
            if found:
                helped += 1
                emitted |= found
                # Sorted so a rebuild is byte-reproducible.
                f.search_extra = " ".join(sorted(found))
        report[lang] = (helped, len(emitted))
    return report


# The same lexicons, written out as an asset so the QUERY side can use them.
#
# A compound in the DATA (`Sødmælk` for a reader typing `mælk`) is handled by an index column. The mirror image -- the compound is in the QUERY, `svinekjøtt` against Norway's
# `Svin, nakkekoteletter` -- cannot be solved that way, because FTS5 has no leading wildcard and
# there is no compound in any name to split. So the app splits the query instead, and it needs
# the same vocabulary to know that `svin` and `kjott` are both real words.
#
# An asset, not a table: it needs no schema change, no `seed.db` rebuild, and no migration.
#
# EVERY language is written, not just COMPOUND_LANGS. The compound splitter still runs only on
# the five compounding languages, but the query side has a second use for the same vocabulary --
# checking that a de-pluralised stem is a real word -- and the plural gap is worst in Spanish and
# Finnish. Spanish does not compound and would have had no lexicon at all.
def compound_lexicons(generic: list[Food]) -> dict[tuple[str, str], set[str]]:
    """{(source, lang): vocabulary} for every generic source. See [lexicon_from_names].

    Keyed per source, not per language: the query side asks whether the reader's own table knows
    a word, and a per-language union would answer with tables the reader is not searching.

    The language rides along because compound splitting is still gated on it (COMPOUND_LANGS);
    carrying it here is what keeps that gate out of a hand-written country table.

    Note this is NOT how [split_compounds] groups. That one enriches `search_extra` on rows,
    and a row belongs to a language, so per-language is right there. Same derivation, two
    groupings, deliberately.
    """
    by_source: dict[tuple[str, str], list[str]] = {}
    for f in generic:
        if f.lang:
            by_source.setdefault((f.source, f.lang), []).append(f.name)
    return {key: lexicon_from_names(names) for key, names in by_source.items()}


def lexicon_lines(lexicons: dict[tuple[str, str], set[str]]) -> list[str]:
    """The asset's lines, sorted. The single definition of the file format.

    Split out because `tools/export_compound_lexicon.py --verify` has to render exactly what
    the writer would write; when it kept its own copy of this f-string, the two could disagree
    about the format and --verify would report a stale asset that was in fact correct.
    """
    return [
        LEX_SEP.join((source, lang, word)) + "\n"
        for source, lang in sorted(lexicons)
        for word in sorted(lexicons[(source, lang)])
    ]


def write_compound_lexicon(
    lexicons: dict[tuple[str, str], set[str]], path: str,
) -> tuple[int, int]:
    """Writes `source<TAB>lang<TAB>word`, sorted, LF-terminated. Returns (sources, words).

    Sorted and explicitly LF so a rebuild is byte-reproducible on any platform -- the same
    reason `search_extra` is sorted where it is written.
    """
    lines = lexicon_lines(lexicons)
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.writelines(lines)
    return len(lexicons), len(lines)


def promote_native_names(generic: list[Food]) -> dict[str, tuple[int, int]]:
    """Makes each national table's own language the language of `foods.name`.

    Returns {source: (promoted, already_native)} for the build report.

    Two traps, neither of which fails loudly, so both are handled here rather
    than being left to seven separate loaders:

    1. A Swedish row whose only remaining alias was the English one would be
       displayed in English to a Swedish reader if the display subquery
       preferred any alias in the reader's list over `f.name`, because
       SettingsManager.foodLanguagesFor always appends 'en'.

       Keeping a copy of the native name as an alias would avoid that, but it
       writes about 21,000 aliases identical to their own food's name, which
       audit_nutrition_db.py rejects. Instead the subquery
       (FoodDao.DISPLAY_NAME) lets an alias win only if the reader ranks its
       language above the row's own language. No duplicate alias is written
       here.
    2. The English name is ADDED as an 'en' alias. `alias_names` is what feeds
       FTS, so without it every national food becomes unreachable by its English
       name -- a straight regression for English readers and for the tests.

    Rows whose native name reads identically to the English one (Tofu, Gin,
    Gazpacho -- the loanword case the loaders already skip when building
    aliases) still have their `lang` set: the name genuinely is the native name,
    it merely coincides. They get no 'en' alias because `name` already serves
    both, and they keep their tier-0 match for English queries.
    """
    moved: dict[str, tuple[int, int]] = {}
    for f in generic:
        target = SWISS_NAME_LANG if f.source == "swiss" else GENERIC_NATIVE_NAME_LANG.get(f.source)
        if target is None:
            continue
        promoted, already = moved.get(f.source, (0, 0))
        native = next((n for lang, n in f.aliases if lang == target), None)
        if native is None:
            # The loanword case: no distinct native alias because it equals the
            # English name. Truthfully still a native name; mark it and move on.
            f.lang = target
            moved[f.source] = (promoted, already + 1)
            continue
        english = f.name
        f.name = native
        f.lang = target
        # The native alias is dropped: `f.name` now IS that language's name, and
        # DISPLAY_NAME reads f.lang, so a second copy would be dead weight the
        # audit rightly objects to. Other languages stay -- Swiss French and
        # Italian, Fineli's Swedish -- because those are names f.name is not.
        # Any pre-existing 'en' alias is replaced rather than duplicated, since
        # UNIQUE(food_id, lang) permits exactly one.
        f.aliases = [(lang, n) for lang, n in f.aliases if lang not in ("en", target)]
        if english and english != native:
            # First, not appended: this alias is how English readers find the row.
            # `alias_names` is " ".join of this list in order, and the alias tier
            # is anchored at its start, so the first alias gets tiers 0-2 and
            # everything after it can only reach 3-4. Appended, "olive oil"
            # returned *Sardines, canned in olive oil, drained* ahead of Ciqual's
            # "Olive oil", which had fallen to tier 3 and lost on length.
            f.aliases.insert(0, ("en", english))
        # Every loader already skips an alias that reads the same as the stored
        # name -- the loanword case. Promotion moves the stored name, so the same
        # rule has to be re-applied against the NEW one: "Banan, rå" is identical
        # in Norwegian and Danish and "Marron glacé" in French and Italian, and
        # 48 such pairs only became duplicates when the native name was promoted.
        f.aliases = [(lang, n) for lang, n in f.aliases if n != f.name]
        moved[f.source] = (promoted + 1, already)
    return moved


# Taxonomy codes that are not a country anyone picks as "theirs". A row whose
# first-listed tag is one of these stores NULL, not the code (off_country
# below). 'world' is the taxonomy pseudo-entry; the territories ARE countries
# in OFF's vocabulary, so they count as usable.
SUPRANATIONAL_CODES = {"world"}


def slug(name: str) -> str:
    """OFF's own slugging rule, as applied to the taxonomy's English names."""
    s = "".join(
        ch for ch in unicodedata.normalize("NFKD", name.lower())
        if unicodedata.category(ch) != "Mn"
    )
    s = s.replace("'", "-").replace("’", "-")
    s = "".join(c if c.isalnum() else "-" for c in s)
    return re.sub("-+", "-", s).strip("-")


def parse_countries_tags(raw: str | None) -> tuple[list[str], list[str]]:
    """(ISO codes in order, unresolved fragments). The clean machine-readable field:
    entries are `en:<slug>` tags joined by commas."""
    if not raw:
        return [], []
    codes: list[str] = []
    unresolved: list[str] = []
    for part in raw.split(","):
        p = part.strip()
        if not p:
            continue
        if p.startswith("en:"):
            code = TAG_TO_ISO.get(p[3:].strip())
        else:
            code = TAG_TO_ISO.get(slug(p))
        if code:
            codes.append(code)
        else:
            unresolved.append(p)
    return codes, unresolved


def off_country(raw: str | None) -> str | None:
    """The `foods.country` value for an Open Food Facts row: the first-listed
    countries_tags code, or NULL.

    NULL means exactly one thing: no preference applies. That covers the three
    cases that all need the same behaviour downstream -- a future per-user
    preference feature will compare `country = :prefCountry`, and none of these
    can match anything a user picks:

      - no countries_tags at all (527 rows in one full build),
      - tags that resolve to no known code (unresolved fragments),
      - a first tag of en:world (137 rows): a real taxonomy entry,
        but not a country anyone picks as "theirs". Storing the string 'world'
        would be behaviourally identical to NULL in that comparison while
        giving NULL two meanings instead of one.

    First-listed is the convention for multi-country rows (13.6% of the
    OFF population): OFF marks no primary sales country, so the first entry
    stands for the row. It is a convention, not a fact about where the product
    is "really" sold -- country of sale is what the field means at all.
    """
    codes, _unresolved = parse_countries_tags(raw)
    if not codes or codes[0] in SUPRANATIONAL_CODES:
        return None
    return codes[0]


# ---------------------------------------------------------------------------
# Open Food Facts
# ---------------------------------------------------------------------------

OFF_COLUMNS = (
    "code", "product_name", "brands",
    "energy-kcal_100g",       # hyphen, not underscore
    "proteins_100g", "fat_100g", "carbohydrates_100g",
    "fiber_100g", "sugars_100g", "saturated-fat_100g", "sodium_100g",
    "serving_size", "serving_quantity", "unique_scans_n",
    "nova_group", "data_quality_errors_tags", "countries_tags",
)

# Open Food Facts runs its own validation and publishes what it finds. Its most common finding is
# the same Atwater mismatch implemented here independently -- but it also detects things this
# script does not, such as sugars-plus-starch exceeding carbohydrate and kcal disagreeing with kJ.
# Deferring to the upstream community's own error flags is strictly better than ignoring them.
OFF_ERROR_SUBSTRINGS = ("energy-value", "value-total-over", "value-over-105", "greater-than")

# `serving_quantity` is a bare number with no unit, and 10.6% of products declare their serving
# in millilitres ("700 ml" gives serving_quantity 700). Storing that as grams is wrong for
# anything that is not roughly water: cod liver oil is 0.92 g/ml, syrup around 1.3.
#
# No source here provides a density, so a volume serving cannot be converted. It is dropped
# instead. A missing serving costs the user one extra tap; a serving that is quietly wrong gets
# logged as fact.
_MASS_SERVING_RE = re.compile(
    r"([\d.,]+)\s*(kg|g|gram(?:me)?s?|oz|ounces?)\b", re.IGNORECASE
)
_VOLUME_SERVING_RE = re.compile(
    r"([\d.,]+)\s*(ml|cl|dl|l|litres?|liters?|fl\.?\s*oz)\b", re.IGNORECASE
)
_MASS_FACTORS = {
    "kg": 1000.0, "g": 1.0, "gram": 1.0, "grams": 1.0, "gramme": 1.0, "grammes": 1.0,
    "oz": 28.349523125, "ounce": 28.349523125, "ounces": 28.349523125,
}


def serving_grams(serving_size: str | None, serving_quantity: float | None) -> float | None:
    """Grams for a declared serving, or None when it is a volume that cannot be converted."""
    text = (serving_size or "").strip()
    if _VOLUME_SERVING_RE.search(text):
        return None
    match = _MASS_SERVING_RE.search(text)
    if match:
        value = to_float(match.group(1))
        factor = _MASS_FACTORS.get(match.group(2).lower())
        if value is not None and factor:
            return value * factor
    # With no unit text at all, serving_quantity is the only signal and OFF documents it as
    # grams. With unit text that parsed as neither mass nor volume, it is not trustworthy.
    return serving_quantity if not text else None


def load_off(path: str, rej: Rejections) -> Iterator[Food]:
    """Streams the export. The file is 1.3 GB gzipped and about 9 GB expanded, so it is never
    materialised -- rows are filtered as they are read."""
    with gzip.open(path, "rt", encoding="utf-8", errors="replace", newline="") as fh:
        reader = csv.reader(fh, delimiter="\t")  # tab-separated despite the .csv extension
        header = next(reader)
        idx = {c: header.index(c) for c in OFF_COLUMNS if c in header}
        missing = [c for c in OFF_COLUMNS if c not in idx]
        if missing:
            print(f"  ! OFF export missing columns: {missing}")
        widest = max(idx.values())

        def col(row, name):
            i = idx.get(name)
            return row[i] if i is not None and i < len(row) else None

        for row in reader:
            rej.seen += 1
            if len(row) <= widest:
                rej.reject("short/malformed row")
                continue

            raw_code = (col(row, "code") or "").strip()
            barcode = clean_barcode(raw_code)
            if not barcode:
                rej.reject(
                    "barcode fails GTIN check digit"
                    if raw_code.isdigit() and len(raw_code) in VALID_BARCODE_LENGTHS
                    else "missing or malformed barcode"
                )
                continue
            name = clean_name(col(row, "product_name"))
            if not name:
                rej.reject("missing or unusable product name")
                continue

            kcal = to_float(col(row, "energy-kcal_100g"))
            protein = to_float(col(row, "proteins_100g"))
            fat = to_float(col(row, "fat_100g"))
            carbs = to_float(col(row, "carbohydrates_100g"))
            reason = passes_quality(kcal, protein, fat, carbs)
            if reason:
                rej.reject(reason)
                continue

            flags = (col(row, "data_quality_errors_tags") or "")
            if any(sub in flags for sub in OFF_ERROR_SUBSTRINGS):
                rej.reject("flagged by Open Food Facts' own data-quality checks")
                continue

            servings = []
            serving_g = serving_grams(
                col(row, "serving_size"), to_float(col(row, "serving_quantity"))
            )
            if serving_g and 0 < serving_g <= 2000:
                # The canonical token, not an English phrase. Open Food Facts gives a serving
                # size with no name, and this is by far the most common portion in the database
                # -- storing prose here put an untranslatable English string on ~97% of portions.
                servings.append((CANONICAL_SERVING, serving_g))

            brand = (col(row, "brands") or "").strip()[:80] or None
            # 3,053 products name themselves after their brand; showing both renders "Pepsi · Pepsi".
            if brand and brand.strip().lower() == name.strip().lower():
                brand = None
            yield Food(
                id=OFF_ID_BASE + int(barcode),
                name=name, brand=brand, barcode=barcode,
                kcal=kcal, protein=protein, fat=fat, carbs=carbs,
                fiber=to_float(col(row, "fiber_100g")),
                sugar=to_float(col(row, "sugars_100g")),
                sat_fat=to_float(col(row, "saturated-fat_100g")),
                sodium=to_float(col(row, "sodium_100g")),
                source="off",
                country=off_country(col(row, "countries_tags")),
                nova=int(to_float(col(row, "nova_group")) or 0) or None,
                popularity=int(to_float(col(row, "unique_scans_n")) or 0),
                servings=clean_servings(servings),
            )


# ---------------------------------------------------------------------------
# Writing
# ---------------------------------------------------------------------------

def create_schema(db: sqlite3.Connection, schema_path: str) -> str:
    """Builds the schema from Room's own exported JSON.

    This is what stops the shipped database from drifting out of sync with the Kotlin entities.
    Executing `setupQueries` verbatim creates `room_master_table` with the identity hash, and
    setting `user_version` completes what Room checks on open.
    """
    with open(schema_path, encoding="utf-8") as fh:
        schema = json.load(fh)["database"]

    for entity in schema["entities"]:
        table = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table))

    for query in schema["setupQueries"]:
        db.execute(query)
    db.execute(f"PRAGMA user_version = {int(schema['version'])}")
    return schema["identityHash"]


def source_versions(args) -> str:
    """Which source releases went into this build, taken from the filenames given."""
    parts = []
    # Every non-repeatable source flag belongs here; a source missing from this list loads but is
    # absent from the recorded provenance.
    for label, value in (("off", args.off), ("ciqual", args.ciqual), ("bls", args.bls),
                         ("bedca", args.bedca), ("cofid", args.cofid),
                         ("matvaretabellen", args.matvaretabellen),
                         ("livsmedel", args.livsmedel), ("fineli", args.fineli),
                         ("frida", args.frida), ("swiss", args.swiss),
                         ("tca", args.tca), ("cnf", args.cnf),
                         ("afcd", args.afcd), ("nevo", getattr(args, "nevo", None)),
                         ("foodfiles", getattr(args, "foodfiles", None))):
        if value:
            # normpath first: a trailing separator would otherwise make basename return ""
            name = os.path.basename(os.path.normpath(str(value)))
            parts.append(f"{label}:{'FOODfiles 2024 v1' if label == 'foodfiles' else name}")
    for path in args.usda or []:
        parts.append(f"usda:{os.path.basename(path)}")
    return ",".join(parts)


# There was once a --market flag here, producing a separate database per country on the
# assumption that national food tables are large. Measured, they are not: at 147 bytes a row
# all-in, every generic food in every language comes to roughly 3 MB against a seed dominated by
# branded Open Food Facts rows. Shipping all languages to everyone is cheaper than the machinery
# deciding which one you get, so language moved onto the rows as `foods.lang` and the variants
# were dropped. `database_meta.market` stays so a file can still say what it is.
MARKET_UNIVERSAL = "universal"


def write_db(
    path: str,
    foods: list[Food],
    schema_path: str,
    version: int = 1,
    sources: str = "",
    search_ids: set[int] | None = None,
    preferred_search_ids: set[int] | None = None,
) -> str:
    # Validate identity before touching the output. In particular, int(barcode) can collide for
    # differently padded GTINs. Never renumber an existing food or silently pick another record.
    # The pinned production inputs have no such collisions; new inputs must fail closed here.
    ids = {f.id for f in foods}
    if len(ids) != len(foods):
        raise ValueError("duplicate food IDs: resolve source identity before building a catalogue")
    if search_ids is None:
        search_ids = ids
    if not search_ids <= ids:
        raise ValueError("search membership references a food outside this catalogue")
    if preferred_search_ids is None:
        preferred_search_ids = search_ids
    if not preferred_search_ids <= ids:
        raise ValueError("preferred search membership references a food outside this catalogue")
    with open(schema_path, encoding="utf-8") as stream:
        food_schema = next(item for item in json.load(stream)["database"]["entities"] if item["tableName"] == "foods")
    nutrition_columns = {item["columnName"] for item in food_schema["fields"]}
    has_nutrition = {"nutrition_basis", "nutrition_metadata"} <= nutrition_columns
    if not has_nutrition and any(f.nutrition_basis != "g" or f.nutrition_metadata for f in foods):
        raise ValueError("Nutrition metadata requires Food Room schema 8 or newer")
    for f in foods:
        if f.nutrition_basis not in {"g", "ml"}:
            raise ValueError("Unknown nutrition basis")
        encode_nutrients(f.nutrition_metadata)
        if f.nutrition_metadata:
            numeric = {"CALORIES": f.kcal, "PROTEIN": f.protein, "FAT": f.fat,
                       "CARBS": f.carbs, "FIBRE": f.fiber, "SUGAR": f.sugar,
                       "SATURATED_FAT": f.sat_fat, "SODIUM": f.sodium}
            for nutrient, item in f.nutrition_metadata.items():
                if item.get("value") != numeric[nutrient]:
                    # A primary unknown uses a numeric subtotal placeholder, explicitly marked
                    # missing. Optional fields retain SQL NULL. Neither becomes a source zero.
                    if not (nutrient in {"CALORIES", "PROTEIN", "FAT", "CARBS"}
                            and item.get("value") is None and numeric[nutrient] == 0):
                        raise ValueError("Nutrient metadata conflicts with stored figure")
    if os.path.dirname(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path):
        os.remove(path)

    db = sqlite3.connect(path)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    identity_hash = create_schema(db, schema_path)

    # The build number goes in the file header as well as in `database_meta`, because the app has
    # to compare it *before* opening the database -- and one of the copies it compares lives in
    # `assets`, where there is nothing to open without extracting 20 MB first.
    # `FoodDatabaseProvider.installSeedIfStale` reads it straight out of the header at offset 68,
    # the same way it already reads `user_version` at offset 60. SQLite reserves `application_id`
    # for exactly this kind of application-private use and never touches it itself; Room does not
    # use it either. VACUUM below preserves it, as it does `user_version`.
    db.execute(f"PRAGMA application_id = {int(version)}")

    db.executemany(
        "INSERT INTO foods (id, name, name_folded, lang, brand, barcode, kcal_100g,"
        " protein_100g, fat_100g, carbs_100g, carbs_convention, fiber_100g, sugar_100g,"
        " sat_fat_100g, sodium_100g, density_g_per_ml, source, quality_score, basic_rank,"
        " source_rank, alias_names, country, search_extra"
        + (", nutrition_basis, nutrition_metadata" if has_nutrition else "") + ") VALUES ("
        + ",".join("?" for _ in range(25 if has_nutrition else 23)) + ")",
        (
            (f.id, f.name, fold_name(f.name), f.lang, f.brand, f.barcode, f.kcal, f.protein,
             f.fat, f.carbs, f.carbs_convention, f.fiber, f.sugar, f.sat_fat, f.sodium,
             f.density, f.source, f.quality, f.basic, source_rank(f.source),
             # Folded and flattened for the FTS index only; never displayed. Null rather than an
             # empty string so the column stays cheap on the rows -- nearly all of them -- that
             # have no aliases at all.
             (" ".join(fold_name(name) for _, name in f.aliases) or None),
             f.country,
             # Already folded by split_compounds; matching only.
             f.search_extra) + ((f.nutrition_basis, encode_nutrients(f.nutrition_metadata)) if has_nutrition else ())
            for f in foods
        ),
    )
    db.executemany(
        "INSERT INTO food_aliases (food_id, lang, name) VALUES (?,?,?)",
        ((f.id, lang, name) for f in foods for lang, name in f.aliases),
    )
    # Every barcode points to its own source record, including records hidden from typed search.
    # Display grouping must never substitute another package's nutrition or serving size.
    db.executemany(
        "INSERT INTO food_barcodes (barcode, food_id) VALUES (?,?)",
        (
            (f.barcode, f.id)
            for f in foods
            if f.barcode
        ),
    )
    db.executemany(
        "INSERT INTO food_servings (food_id, label, grams, is_default, sort_order)"
        " VALUES (?,?,?,?,?)",
        (
            (f.id, label, grams, 1 if i == 0 else 0, i)
            for f in foods
            for i, (label, grams) in enumerate(f.servings)
        ),
    )

    db.execute(
        "INSERT INTO database_meta (id, market, version, built_at, source_versions, food_count)"
        " VALUES (1, ?, ?, ?, ?, ?)",
        (MARKET_UNIVERSAL, version, int(time.time() * 1000), sources, len(foods)),
    )

    # Search membership is distinct from food identity. The external-content view excludes scan-
    # only records even on an FTS rebuild; merely inserting a subset into an index whose content
    # is `foods` would make rebuild index every hidden variant again. Queries still join rowid to
    # foods, and Room's entity schema is unchanged. These are builder-owned search structures.
    db.execute("CREATE TABLE food_search_members (food_id INTEGER PRIMARY KEY, preferred INTEGER NOT NULL)")
    db.executemany("INSERT INTO food_search_members VALUES (?, ?)",
                   ((i, int(i in preferred_search_ids)) for i in sorted(search_ids)))
    db.execute(
        "CREATE VIEW food_search_content AS"
        " SELECT f.id, f.name_folded, f.brand, f.alias_names, f.search_extra"
        " FROM foods f JOIN food_search_members m ON m.food_id = f.id"
    )
    # External-content FTS5 references that view rather than duplicating its text.
    # Room has no @Fts5 annotation, so this table is created here and queried with
    # @SkipQueryVerification. Room ignores tables it does not know about.
    #
    # `alias_names` is the third column so a French or Spanish query reaches the English record
    # it names. It has to be a real column on `foods` rather than a join to `food_aliases`,
    # because external content means FTS5 re-reads its columns from the content table by name.
    #
    # The first column is `name_folded`, not `name`. `remove_diacritics 2` already handles accents
    # on both sides, so that is not what this is for -- it is the ligatures. Unicode treats oe, ae,
    # ss and ij as letters in their own right, not as accented forms, so the tokenizer leaves them
    # intact and `oeuf` could never reach *Oeuf, cru* while the index held the raw name.
    # `fold_name` is the only thing that splits them, and `alias_names` was already stored folded,
    # so indexing the raw name also meant the index's first column and its third disagreed about
    # what a word is.
    #
    # This has a hard dependency on the app: `FtsQuery.sanitize` must fold the query with
    # `foldForSearch` before matching against it. Folding one side alone is worse than folding
    # neither -- a folded index and an unfolded query stop matching for anyone who types the
    # ligature. A database built without folding must not be served to an app that folds, which
    # is what the build-number check above is for.
    db.executescript(
        """
        CREATE VIRTUAL TABLE food_search USING fts5(
            name_folded, brand, alias_names, search_extra,
            content='food_search_content', content_rowid='id',
            tokenize='unicode61 remove_diacritics 2'
        );
        INSERT INTO food_search(food_search) VALUES('rebuild');
        """
    )
    db.commit()
    db.execute("ANALYZE")
    db.execute("VACUUM")
    db.close()
    return identity_hash


def stub_check(path: str, schema_path: str, expected_hash: str, version: int) -> int:
    """Verifies what a zero-row database can actually get wrong.

    Deliberately NOT `self_check`, which asserts the foods table is populated and that a
    search for "banana" returns something. Both are right for a real build and both are
    meaningless here -- a stub has no rows by construction. What still has to hold is
    everything Room and `FoodDatabaseProvider` look at before reading a single row.
    """
    db = sqlite3.connect(path)
    checks = []

    row = db.execute("SELECT identity_hash FROM room_master_table WHERE id = 42").fetchone()
    checks.append(("room_master_table hash matches the Room schema",
                   row is not None and row[0] == expected_hash, row and row[0]))

    user_version = db.execute("PRAGMA user_version").fetchone()[0]
    with open(schema_path, encoding="utf-8") as fh:
        expected_version = int(json.load(fh)["database"]["version"])
    checks.append(("user_version matches the schema",
                   user_version == expected_version, user_version))

    app_id = db.execute("PRAGMA application_id").fetchone()[0]
    checks.append(("application_id is the build number", app_id == int(version), app_id))

    integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
    checks.append(("integrity_check clean", integrity == "ok", integrity))

    meta = db.execute("SELECT food_count FROM database_meta WHERE id = 1").fetchone()
    checks.append(("database_meta row exists, zero foods",
                   meta is not None and meta[0] == 0, meta and meta[0]))

    # The tables the app queries must exist even though they are empty, or Room fails on
    # open rather than simply returning nothing.
    names = {r[0] for r in db.execute(
        "SELECT name FROM sqlite_master WHERE type IN ('table','view')")}
    for table in ("foods", "food_aliases", "food_servings", "food_barcodes",
                  "database_meta", "food_search"):
        checks.append((f"{table} exists", table in names, table in names))
    db.close()

    print(f"\n  stub database written to {path}")
    print(f"      0 foods, schema {os.path.basename(schema_path)}, "
          f"identity {expected_hash}")
    ok = True
    for label, passed, actual in checks:
        print(f"      [{'ok' if passed else 'FAIL'}] {label} -- {actual}")
        ok &= bool(passed)
    return 0 if ok else 1


def self_check(path: str, schema_path: str, expected_hash: str) -> bool:
    print(f"\n  self-check {path}")
    db = sqlite3.connect(path)
    ok = True

    def check(label: str, passed: bool, detail: str = "") -> None:
        nonlocal ok
        ok &= passed
        print(f"      [{'ok' if passed else 'FAIL'}] {label}{' — ' + detail if detail else ''}")

    n_foods = db.execute("SELECT COUNT(*) FROM foods").fetchone()[0]
    check("foods table is populated", n_foods > 0, f"{n_foods:,} rows")

    n_fts = db.execute("SELECT COUNT(*) FROM food_search").fetchone()[0]
    n_search = db.execute("SELECT COUNT(*) FROM food_search_members").fetchone()[0]
    check("FTS content matches search membership", n_fts == n_search,
          f"{n_fts:,} searchable / {n_foods:,} stored")
    # COUNT(*) on external-content FTS reads the view, not the index. rank=1 also checks the
    # actual indexed tokens against their content, catching missing or extra indexed records.
    try:
        db.execute("INSERT INTO food_search(food_search, rank) VALUES('integrity-check', 1)")
        check("FTS tokens match external content", True)
    except sqlite3.DatabaseError as exc:
        check("FTS tokens match external content", False, str(exc))

    nulls = db.execute(
        "SELECT COUNT(*) FROM foods WHERE name IS NULL OR kcal_100g IS NULL"
        " OR protein_100g IS NULL OR fat_100g IS NULL OR carbs_100g IS NULL"
        " OR source IS NULL OR quality_score IS NULL"
    ).fetchone()[0]
    check("no NULLs in NOT NULL columns", nulls == 0)

    integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
    check("integrity_check clean", integrity == "ok", integrity)

    row = db.execute("SELECT identity_hash FROM room_master_table WHERE id = 42").fetchone()
    check(
        "room_master_table hash matches the Room schema",
        bool(row) and row[0] == expected_hash,
        (row[0] if row else "missing"),
    )

    version = db.execute("PRAGMA user_version").fetchone()[0]
    with open(schema_path, encoding="utf-8") as fh:
        expected_version = json.load(fh)["database"]["version"]
    check("user_version set", version == expected_version, f"{version}")

    bad_energy = db.execute(
        f"SELECT COUNT(*) FROM foods WHERE kcal_100g < {MIN_KCAL} OR kcal_100g > {MAX_KCAL}"
    ).fetchone()[0]
    check("all energy values in range", bad_energy == 0)

    orphans = db.execute(
        "SELECT COUNT(*) FROM food_servings s LEFT JOIN foods f ON f.id = s.food_id"
        " WHERE f.id IS NULL"
    ).fetchone()[0]
    check("no orphaned servings", orphans == 0)

    hit = db.execute(
        "SELECT f.name FROM food_search s JOIN foods f ON f.id = s.rowid"
        " WHERE food_search MATCH 'milk' LIMIT 1"
    ).fetchone()
    check("FTS query returns results", hit is not None, hit[0] if hit else "no match")

    # `food_barcodes` is the only key the app matches a scan against, so a row whose own barcode
    # is missing from it is invisible to the scanner while looking healthy in search.
    unreachable = db.execute(
        "SELECT COUNT(*) FROM foods f WHERE f.barcode IS NOT NULL AND NOT EXISTS"
        " (SELECT 1 FROM food_barcodes b WHERE b.barcode = f.barcode AND b.food_id = f.id)"
    ).fetchone()[0]
    check("every food's own barcode is scannable", unreachable == 0, f"{unreachable:,}")

    n_codes, n_own = db.execute(
        "SELECT (SELECT COUNT(*) FROM food_barcodes),"
        " (SELECT COUNT(*) FROM foods WHERE barcode IS NOT NULL)"
    ).fetchone()
    check("one source record per barcode", n_codes == n_own,
          f"{n_codes:,} codes / {n_own:,} records")

    db.close()
    print(f"      size: {os.path.getsize(path) / 1024 / 1024:.1f} MB")

    # Structural checks above prove the file is loadable. The audit proves the data inside it is
    # not junk -- a separate question, and the one that actually reaches users.
    ok &= audit(path)
    return ok


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def display_key(food: Food) -> tuple[str, str, int]:
    """The existing display group, never a statement that two records share nutrition."""
    return (food.name.strip().lower(), (food.brand or "").strip().lower(), int(food.kcal // 50))


@dataclass
class DisplayGroup:
    winner: Food
    members: list[Food]


def group_display_duplicates(foods: list[Food]) -> list[DisplayGroup]:
    """Select the same popularity/quality winner, retaining every member's original data.

    OFF's floor and catalogue limits apply to winners, as before. Members of a selected group
    remain available by barcode even below the scan floor, with their OWN nutrition and servings.
    """
    groups: dict[tuple, DisplayGroup] = {}
    for food in foods:
        key = display_key(food)
        group = groups.get(key)
        if group is None:
            groups[key] = DisplayGroup(food, [food])
        else:
            group.members.append(food)
            if (food.popularity, food.quality) > (group.winner.popularity, group.winner.quality):
                group.winner = food
    return list(groups.values())


def established_generic_display_rows(foods: list[Food]) -> list[Food]:
    """Derive the established presentation choice, including its original alias ordering.

    Applied to a deep copy across sources solely to derive equal-match preference. It never
    decides which source records are stored; generic_display_rows scopes actual folding by source.
    """
    def fold_aliases(winner: Food, loser: Food) -> None:
        for lang, name in loser.aliases:
            if name != winner.name and lang not in {a[0] for a in winner.aliases}:
                winner.aliases.append((lang, name))

    best: dict[tuple, Food] = {}
    for food in foods:
        key = display_key(food)
        current = best.get(key)
        if current is None:
            best[key] = food
        elif (food.popularity, food.quality) > (current.popularity, current.quality):
            fold_aliases(food, current)
            best[key] = food
        else:
            fold_aliases(current, food)
    return list(best.values())


def generic_display_rows(foods: list[Food]) -> list[Food]:
    """Select display representatives within a source, retaining source identity and nutrition.

    Alias folding is restricted to the same source. Callers retain the complete input catalogue;
    this function selects search membership only, never deletes a national-table record.
    """
    sources: dict[str, list[Food]] = {}
    for food in foods:
        sources.setdefault(food.source, []).append(food)
    return [winner for rows in sources.values() for winner in established_generic_display_rows(rows)]


# Sources whose equal-match display preference is derived apart from the established tables.
SEPARATE_PREFERENCE_SOURCES = frozenset({"foodfiles"})


def _preference_groups(foods: list[Food]) -> list[list[Food]]:
    shared = [f for f in foods if f.source not in SEPARATE_PREFERENCE_SOURCES]
    separate = [[f for f in foods if f.source == s] for s in sorted(SEPARATE_PREFERENCE_SOURCES)]
    return [shared] + [group for group in separate if group]


def rank_name(food: Food) -> str:
    """The English name basic_rank's marker lists are written against.

    Every other national table still carries its English name in `name` at this point and is
    promoted to the native one later. NEVO's loader writes the Dutch name directly, so ranking
    `name` gave all 2,328 NEVO rows "ordinary" -- `Wortel blik/glas` tied `Wortel rauw` and won on
    brevity, `Aardappelzetmeel` (starch) answered `aardapp`. Its English alias is what the
    publisher supplies as the same food's English name.
    """
    if food.source == "nevo":
        for lang, name in food.aliases:
            if lang == "en":
                return name
    return food.name


def prepare_generic_catalogue(foods: list[Food]) -> tuple[list[Food], set[int]]:
    """Restore each source while preserving the established display choice for equal matches.

    The preference is derived from the same input records and original selection algorithm,
    not a shipped list of historical IDs or query-specific boosts. Stronger matches can still
    outrank these representatives; equal matches retain their established presentation.
    """
    # FOODfiles shares USDA's comma style; deriving it separately keeps its rows from replacing
    # established representatives in other countries' lists.
    preferred_ids: set[int] = set()
    for group in _preference_groups(foods):
        established = established_generic_display_rows(copy.deepcopy(group))
        promote_native_names(established)
        established = established_generic_display_rows(established)
        preferred_ids |= {food.id for food in established}
    search = generic_display_rows(foods)
    promote_native_names(foods)
    search = generic_display_rows(search)
    return search, preferred_ids


def branded_display_groups(foods: list[Food], min_scans: int) -> list[DisplayGroup]:
    """Apply the established scan floor to display winners while keeping their siblings."""
    groups = group_display_duplicates(foods)
    groups.sort(key=lambda g: (g.winner.popularity, g.winner.quality), reverse=True)
    # Applying the floor to members would make 121,266 codes unscannable.
    kept = [g for g in groups if g.winner.popularity >= min_scans]
    print(f"\n  scan floor: dropped {len(groups) - len(kept):,} display groups, "
          f"{len(kept):,} remain")
    return kept


def select_catalogue(
    generic: list[Food], generic_search: list[Food], groups: list[DisplayGroup], limit: int,
) -> tuple[list[Food], set[int]]:
    """Limit branded display groups, then retain every member of the selected groups.

    A branded display winner covered by a generic result stays hidden in typed search. Its
    group's barcodes still resolve to their own package data rather than a national-table row.
    """
    selected = groups[:limit]
    generic_keys = {display_key(f) for f in generic_search}
    search_ids = {f.id for f in generic_search}
    search_ids.update(g.winner.id for g in selected if display_key(g.winner) not in generic_keys)
    foods = generic + [f for group in selected for f in group.members]
    print(f"      {len(foods):,} source records / {len(search_ids):,} search rows")
    return foods, search_ids


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--off", help="Path to en.openfoodfacts.org.products.csv.gz")
    p.add_argument("--usda", action="append", default=[], help="USDA FDC csv zip (repeatable)")
    p.add_argument("--ciqual", help="Directory holding the Ciqual XML files")
    p.add_argument("--bls", help="Path to the BLS CSV written by tools/extract_bls_csv.py")
    p.add_argument("--bedca", help="Path to the BEDCA wide CSV (bedca_foods.csv)")
    p.add_argument("--cofid", help="Path to the CoFID CSV written by tools/extract_cofid_csv.py")
    p.add_argument("--matvaretabellen", help="Directory holding foods_en.json / foods_nb.json (Norway)")
    p.add_argument("--livsmedel", help="Directory holding foods_sprak1/2.json and naringsvarden.jsonl (Sweden)")
    p.add_argument("--fineli", help="Directory holding the Fineli open-data CSV release (Finland)")
    p.add_argument("--frida", help="Path to the Frida CSV written by tools/extract_frida_csv.py")
    p.add_argument("--swiss", help="Path to the Swiss CSV written by tools/extract_swiss_csv.py")
    p.add_argument("--tca", help="Path to the TCA CSV written by tools/extract_tca_csv.py")
    p.add_argument("--cnf", help="Path to the Canadian Nutrient File zip (cnf_2026.zip)")
    p.add_argument("--afcd", help="Path to the AFCD CSV written by tools/extract_afcd_csv.py")
    p.add_argument("--nevo", help="NEVO 2025/9.0 wide CSV; Details and Nutrienten_Nutrients siblings required")
    p.add_argument("--foodfiles", help="FOODfiles 2024 v1 StandardDataFT; Names, StandardCodes and Csm siblings required")
    p.add_argument("--schema", required=True, help="Room's exported FoodDatabase schema JSON")
    p.add_argument("--out", required=True, help="Full database output path")
    p.add_argument("--seed", help="Seed database output path (bundled in the APK)")
    p.add_argument("--lexicon", default="app/src/main/assets/compound-lexicon.txt",
                   help="Where to write the query-side compound lexicon asset. "
                        "Derived from the generic names, so it must be rewritten whenever they "
                        "change; tools/export_compound_lexicon.py regenerates it from a built db.")
    p.add_argument("--full-limit", type=int, default=500_000,
                   help="Max branded display groups in the full database (default 500000)")
    p.add_argument("--seed-limit", type=int, default=50_000,
                   help="Max branded display groups in the seed database (default 50000)")
    p.add_argument("--min-scans", type=int, default=0,
                   help="Drop branded display groups whose winner has fewer recorded scans "
                        "(unique_scans_n). A floor, not a ranking -- see the comment at the "
                        "use site. Default 0 keeps every row.")
    p.add_argument("--build-version", type=int, default=1,
                   help="Monotonic build number, written into the database so the app can tell "
                        "whether a download is newer than what is installed.")
    p.add_argument("--manifest", help="Write a release manifest describing the outputs")
    p.add_argument("--self-check", action="store_true")
    p.add_argument(
        "--stub",
        action="store_true",
        help="Write a schema-correct database with ZERO rows to --out and exit, without "
             "reading any source. For CI, where app/src/main/assets/seed.db is absent "
             "because it is gitignored, and Gradle refuses to run at all without it.",
    )
    args = p.parse_args()

    # A clean clone cannot build: checkSeedDatabase hangs off preBuild, so testDebugUnitTest
    # and lintDebug fail too, not just assembleDebug. A runner is a clean clone. This is the
    # smallest thing that satisfies that gate -- real schema, real identity hash, real
    # database_meta, no rows -- and it reuses write_db rather than reimplementing it, so it
    # cannot drift from what a real build produces.
    #
    # It is not a substitute for the real database: search returns nothing against it.
    if args.stub:
        # The repo's first standing constraint is that overwriting app/src/main/assets/seed.db as
        # a side effect is unrecoverable -- it is gitignored, so there is no copy to restore from.
        # A stub is a 68 KB file that would silently replace a 114 MB one, and this command is
        # short enough to copy out of the CI workflow and run by hand. So it refuses rather than
        # clobbers. On a runner the path does not exist and this never fires.
        if os.path.exists(args.out):
            for line in (
                f"refusing to overwrite {args.out}: it already exists.",
                "  A stub has zero rows. If that is a real database, replacing it with this",
                "  would destroy it, and it is gitignored, so nothing could restore it.",
                "  Delete it deliberately first, or write the stub somewhere else.",
            ):
                print(line, file=sys.stderr)
            return 1
        identity_hash = write_db(args.out, [], args.schema, args.build_version, "stub")
        return stub_check(args.out, args.schema, identity_hash, args.build_version)

    t0 = time.time()
    print("Building nutrition database")
    print("=" * 70)

    generic: list[Food] = []

    if args.ciqual:
        print("\nCiqual")
        rej = Rejections()
        found = load_ciqual(args.ciqual, rej)
        rej.report("Ciqual", len(found))
        generic += found

    if args.usda:
        print("\nUSDA FoodData Central")
        rej = Rejections()
        found = load_usda(args.usda, rej)
        rej.report("USDA", len(found))
        generic += found

    if args.bls:
        print("\nGerman BLS (Bundeslebensmittelschluessel)")
        rej = Rejections()
        found = load_bls(args.bls, rej)
        rej.report("BLS", len(found))
        generic += found

    if args.bedca:
        print("\nSpanish BEDCA")
        rej = Rejections()
        found = load_bedca(args.bedca, rej)
        rej.report("BEDCA", len(found))
        generic += found

    if args.cofid:
        print("\nUK CoFID (Composition of Foods Integrated Dataset)")
        rej = Rejections()
        found = load_cofid(args.cofid, rej)
        rej.report("CoFID", len(found))
        generic += found

    if args.matvaretabellen:
        print("\nNorwegian Matvaretabellen")
        rej = Rejections()
        found = load_matvaretabellen(args.matvaretabellen, rej)
        rej.report("Matvaretabellen", len(found))
        generic += found

    if args.livsmedel:
        print("\nSwedish Livsmedelsdatabasen")
        rej = Rejections()
        found = load_livsmedel(args.livsmedel, rej)
        rej.report("Livsmedelsdatabasen", len(found))
        generic += found

    if args.fineli:
        print("\nFinnish Fineli")
        rej = Rejections()
        found = load_fineli(args.fineli, rej)
        rej.report("Fineli", len(found))
        generic += found

    if args.frida:
        print("\nDanish Frida")
        rej = Rejections()
        found = load_frida(args.frida, rej)
        rej.report("Frida", len(found))
        generic += found

    if args.swiss:
        print("\nSwiss FCDB (Schweizer Nahrwertdatenbank)")
        rej = Rejections()
        found = load_swiss(args.swiss, rej)
        rej.report("Swiss FCDB", len(found))
        generic += found

    if args.tca:
        print("\nPortuguese TCA (Tabela da Composicao de Alimentos, INSA)")
        rej = Rejections()
        found = load_tca(args.tca, rej)
        rej.report("TCA", len(found))
        generic += found

    if args.cnf:
        print("\nCanadian Nutrient File")
        rej = Rejections()
        found = load_cnf(args.cnf, rej)
        rej.report("CNF", len(found))
        generic += found

    if args.afcd:
        print("\nAustralian AFCD (Australian Food Composition Database)")
        rej = Rejections()
        found = load_afcd(args.afcd, rej)
        rej.report("AFCD", len(found))
        generic += found

    if args.nevo:
        print("\nDutch NEVO 2025/9.0")
        found = load_nevo(args.nevo)
        print(f"  NEVO: read and retained {len(found):,}; no source values amended")
        generic += found

    if args.foodfiles:
        print("\nNew Zealand FOODfiles 2024 v1")
        args.foodfiles_report = {}
        found = load_foodfiles(args.foodfiles, args.foodfiles_report)
        print("  FOODfiles reconciliation: " + json.dumps(args.foodfiles_report, ensure_ascii=False))
        generic += found

    # Attached here rather than per-loader, so it is one rule over the whole generic
    # tier: only foods that survived their loader's quality filter are in `generic`,
    # and branded foods never are. Ciqual foods keep their `fr` alias alongside this,
    # and BLS foods keep their `de` one -- the BLS load runs above deliberately, so
    # its English names get the same British vocabulary pass ("Yogurt mild 3.5 % fat"
    # -> "Yoghurt mild 3.5 % fat") rather than being the one generic source that
    # silently misses it.
    #
    # TCA is excluded by source, not by f.lang: its names are Portuguese regardless
    # of what TCA_NAME_LANG marks them for ranking, and the pass matched "Lima"
    # (the lime) against the lima-bean pair, turning it into a "Butter" alias.
    # A source asked for on the command line that produced NOTHING is a failed
    # build, not a warning. For example, BEDCA's loader reads a sibling file,
    # bedca_foodvalues.csv, from next to the path given for --bedca. Without it
    # all 957 rows fail the "no energy value" gate, yet the build would print one
    # "!" line, pass --self-check and the audit, and exit 0 with Spain empty.
    #
    # Same rule GENERIC_SOURCE_COUNTRY already applies by being indexed rather
    # than .get(): a half-configured source fails loudly instead of quietly
    # shrinking the database.
    requested = {
        "usda": bool(args.usda), "ciqual": args.ciqual, "bls": args.bls,
        "bedca": args.bedca, "cofid": args.cofid,
        "matvaretabellen": args.matvaretabellen, "livsmedel": args.livsmedel,
        "fineli": args.fineli, "frida": args.frida, "swiss": args.swiss,
        "tca": args.tca, "cnf": args.cnf, "afcd": args.afcd, "nevo": args.nevo,
        "foodfiles": args.foodfiles,
    }
    loaded = {f.source for f in generic}
    empty = sorted(n for n, asked in requested.items() if asked and n not in loaded)
    if empty:
        raise SystemExit(
            "\nBUILD FAILED: requested but produced 0 rows: " + ", ".join(empty)
            + "\n  A loader that finds its file but keeps nothing usually means a"
              "\n  companion file is missing beside it -- check the '!' lines above."
        )

    for f in generic:
        if f.source in {"tca", "nevo", "foodfiles"}:
            continue
        gb = british_name(f.name)
        if gb:
            f.aliases.append(("en-GB", gb))

    # Country by the same "one rule over the whole generic tier" shape as the
    # British pass above: a generic row's country is its national table's.
    # Indexed, not .get(), so a new national table that forgets to declare
    # itself in GENERIC_SOURCE_COUNTRY fails the build loudly rather than
    # silently shipping a NULL country.
    for f in generic:
        f.country = GENERIC_SOURCE_COUNTRY[f.source]

    branded: list[Food] = []
    if args.off:
        print("\nOpen Food Facts (streaming, this takes a few minutes)")
        rej = Rejections()
        by_barcode: dict[str, Food] = {}
        for food in load_off(args.off, rej):
            food.quality = quality_score(food)
            prev = by_barcode.get(food.barcode)
            # Duplicate barcodes exist (regional variants, re-listings). Keep the record with
            # more real data, breaking ties on how often it has actually been scanned.
            if prev is None or (food.quality, food.popularity) > (prev.quality, prev.popularity):
                by_barcode[food.barcode] = food
            elif prev is not None:
                rej.reject("duplicate barcode (kept the more complete record)")
            if rej.seen % 500_000 == 0:
                print(f"      ... {rej.seen:,} rows scanned, {len(by_barcode):,} kept")
        branded = list(by_barcode.values())
        rej.report("Open Food Facts", len(branded))

    for f in generic:
        f.quality = quality_score(f)
    for f in generic + branded:
        if f.source not in {"nevo", "foodfiles"}:
            clean_micros(f)
        f.basic = basic_rank(rank_name(f), f.source, f.nova)

    # Compute ranks from original English names, then promote every retained source record.
    # Display membership and its equal-match preference are separate from record identity.
    generic_search, preferred_generic_ids = prepare_generic_catalogue(generic)

    # After promotion: the lexicon must be built from the stored (native) names.
    for lang, (helped, distinct) in sorted(split_compounds(generic_search).items()):
        print(f"      compound heads: {lang} {helped} rows, {distinct} distinct heads")

    # From the same names split_compounds used, or the query side would accept splits the index
    # cannot answer.
    if args.lexicon:
        sources, words = write_compound_lexicon(compound_lexicons(generic_search), args.lexicon)
        print(f"      compound lexicon: {words:,} words across {sources} source/language pairs "
              f"-> {args.lexicon}")

    groups = branded_display_groups(branded, args.min_scans)

    print("\n  full database")
    full, full_search = select_catalogue(generic, generic_search, groups, args.full_limit)
    sources = source_versions(args)
    identity_hash = write_db(
        args.out, full, args.schema, args.build_version, sources, full_search, preferred_generic_ids
    )

    ok = True
    if args.self_check:
        ok &= self_check(args.out, args.schema, identity_hash)

    if args.seed:
        print("\n  seed database")
        seed, seed_search = select_catalogue(generic, generic_search, groups, args.seed_limit)
        write_db(args.seed, seed, args.schema, args.build_version, sources, seed_search, preferred_generic_ids)
        if args.self_check:
            ok &= self_check(args.seed, args.schema, identity_hash)

    if args.manifest:
        write_manifest(args.manifest, args, identity_hash)

    print(f"\nDone in {time.time() - t0:.0f}s")
    return 0 if ok else 1


def write_manifest(path: str, args, identity_hash: str) -> None:
    """Describes the built artifacts for the in-app updater.

    Written before the in-app updater exists, on purpose: the app has to be able to ask "what is
    available, and is it newer than mine" from the very first release. Retrofitting that means
    re-publishing artifacts people already downloaded and reworking an update flow that is by then
    in use.

    `schemaIdentityHash` is the load-bearing field. The app compares it against what its own Room
    entities expect and refuses a mismatch, so a database built for a newer app version is
    rejected cleanly rather than crashing an older one.
    """
    def describe(db_path: str) -> dict | None:
        if not db_path or not os.path.exists(db_path):
            return None
        digest = hashlib.sha256()
        with open(db_path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        conn = sqlite3.connect(db_path)
        try:
            foods = conn.execute("SELECT COUNT(*) FROM foods").fetchone()[0]
        finally:
            conn.close()
        return {
            "file": os.path.basename(db_path),
            "bytes": os.path.getsize(db_path),
            "sha256": digest.hexdigest(),
            "foods": foods,
        }

    manifest = {
        "manifestVersion": 1,
        "market": MARKET_UNIVERSAL,
        "version": args.build_version,
        "builtAt": int(time.time() * 1000),
        "schemaIdentityHash": identity_hash,
        "sourceVersions": source_versions(args),
        "foodfilesImport": getattr(args, "foodfiles_report", None),
        "full": describe(args.out),
        "seed": describe(args.seed),
    }
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2)
    print(f"\n  manifest written to {path}")


if __name__ == "__main__":
    sys.exit(main())
