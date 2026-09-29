#!/usr/bin/env python3
'Build SQLite food assets from local nutrition datasets. Use --out app/src/main/assets/seed.db and --schema app/schemas/com.lethio.macros.data.food.FoodDatabase/7.json.'

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
from audit_nutrition_db import audit

csv.field_size_limit(10**9)

CIQUAL_ID_BASE = 100_000_000
USDA_ID_BASE = 200_000_000
OFF_ID_BASE = 1_000_000_000_000

MIN_NAME_LEN, MAX_NAME_LEN = 2, 120

MIN_KCAL, MAX_KCAL = 0.0, 900.0
MAX_MACRO_G = 100.0
MAX_MACRO_SUM_G = 105.0
ATWATER_ABS_FLOOR = 35.0
ATWATER_REL_TOLERANCE = 0.30

ZERO_KCAL_MACRO_LIMIT = 0.5
VALID_BARCODE_LENGTHS = (8, 12, 13, 14)

MIN_SERVING_G, MAX_SERVING_G = 1.0, 2000.0
MAX_SERVINGS_PER_FOOD = 8

CANONICAL_SERVING = "serving"

KCAL_PER_G = {"protein": 4.0, "fat": 9.0, "carbs": 4.0}

BASIC_RANK_WHOLE = 0
BASIC_RANK_ORDINARY = 1
BASIC_RANK_DERIVATIVE = 2

DERIVATIVE_MARKERS = (
    "flour", "powder", "starch", "bran", "meal", "isolate", "concentrate", "extract",
    "dried", "dehydrated", "syrup", "nectar", "juice", "paste", "puree", "purée",
    "tart", "pie", "cake", "biscuit", "cookie", "pastry", "candy", "jam", "jelly", "oil",
)

SOURCE_EXEMPT_MARKERS: dict[str, frozenset[str]] = {
    "usda": frozenset({"biscuit"}),
}

DERIVATIVE_COMPOUNDS = (

    "cheesecake", "cheescake", "pancake", "oatcake", "fishcake", "hotcake", "minicake",
    "teacake", "rice-cake", "plumcake", "shortcake",

    "cornflour", "cornstarch", "oatbran", "sundried",

    "cornmeal",

    "tarte", "tartelette", "tartelete", "tartelle", "tarta", "tartine", "tartiner",
    "tartinade", "tartinable", "tartinata", "tartinette", "tartiflette", "tarteteig",

    "brandade", "tomatenpuree", "powdered", "jellybean", "candymix", "currypaste",
    "sesampaste", "tomatjuice", "pastete", "pastel", "biscuiti", "biscuithé",
    "boterbiscuit", "syrupwith", "concentrated", "pastryt", "nectarde", "starchy",
)

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

_MARKER_RE_BY_SOURCE: dict[str, re.Pattern[str]] = {
    source: _compile_markers(tuple(m for m in DERIVATIVE_MARKERS if m not in exempt))
    for source, exempt in SOURCE_EXEMPT_MARKERS.items()
}
_DERIVATIVE_COMPOUND_RE = re.compile(
    r"(?<![a-z])(?:" + "|".join(sorted(re.escape(c) + r"(?:s(?![a-z]))?(?![a-z])" for c in DERIVATIVE_COMPOUNDS)) + r")"
)

WHOLE_FOOD_MARKERS = (
    "raw", "fresh", "whole", "unprocessed", "plain",

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

    carbs_convention: str = "available"

    fiber: float | None = None
    sugar: float | None = None
    sat_fat: float | None = None
    sodium: float | None = None
    density: float | None = None
    source: str = "off"

    country: str | None = None

    quality: int = 0
    basic: int = BASIC_RANK_ORDINARY
    popularity: int = 0
    nova: int | None = None
    servings: list[tuple[str, float]] = field(default_factory=list)

    lang: str | None = None

    search_extra: str | None = None

    aliases: list[tuple[str, str]] = field(default_factory=list)

class Rejections:

    def __init__(self) -> None:
        self.counts: dict[str, int] = {}
        self.seen = 0

    def reject(self, reason: str) -> None:
        self.counts[reason] = self.counts.get(reason, 0) + 1

    def report(self, label: str, kept: int) -> None:
        print(f"\n  {label}: read {self.seen:,}, kept {kept:,}")
        for reason, n in sorted(self.counts.items(), key=lambda kv: -kv[1]):
            print(f"      rejected {n:>9,}  {reason}")

_TRACE_RE = re.compile(r"^\s*<\s*([\d.,]+)\s*$")

def to_float(raw: str | None) -> float | None:

    if raw is None:
        return None
    s = raw.strip()
    if not s or s == "-":
        return None
    if s.lower() in ("traces", "trace"):
        return 0.0
    m = _TRACE_RE.match(s)
    if m:
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

    stripped = "".join(
        ch
        for ch in unicodedata.normalize("NFKD", name)
        if unicodedata.category(ch) != "Mn"
    )

    return "".join(LIGATURES.get(ch, ch) for ch in stripped.lower())

LIGATURES = {

    "œ": "oe",
    "æ": "ae",
    "ß": "ss",
    "ĳ": "ij",

    "ø": "o",
    "ł": "l",
    "ð": "d",
}

from audit_nutrition_db import GENERIC_SOURCES
SOURCE_RANK_GENERIC = 0
SOURCE_RANK_BRANDED = 1

def source_rank(source: str) -> int:
    return SOURCE_RANK_GENERIC if source in GENERIC_SOURCES else SOURCE_RANK_BRANDED

DEGENERATE_SERVING_LABELS = {
    "g", "gram", "grams", "kg", "oz", "ounce", "ounces", "fl oz", "fl. oz",
    "ml", "millilitre", "milliliter", "l", "litre", "liter", "lb", "pound",
    "quantity not specified", "undetermined",
}

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

    cleaned = _PARENTHETICAL.sub("", label).strip().strip(",").strip()
    if not cleaned:
        return None

    lowered = cleaned.lower()
    if lowered in _CANONICAL_LOOKUP:
        return _CANONICAL_LOOKUP[lowered]

    head = lowered.split(",")[0].strip()
    if head in _CANONICAL_LOOKUP:
        return _CANONICAL_LOOKUP[head]

    return cleaned if len(cleaned) <= 24 else None

def is_named_serving(label: str) -> bool:

    return label.strip().lower().rstrip(".") not in DEGENERATE_SERVING_LABELS

_HTML_ENTITY = re.compile(r"&(?:amp|quot|apos|lt|gt|nbsp|#\d+|#x[0-9a-fA-F]+);")
_URL = re.compile(r"https?://|www\.", re.IGNORECASE)

def clean_name(raw: str | None) -> str | None:

    if not raw:
        return None

    name = raw

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

    if not any(c.isalpha() for c in name):
        return None
    return name

def gtin_check_digit_ok(code: str) -> bool:

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

    if kcal == 0 and (protein > ZERO_KCAL_MACRO_LIMIT or fat > ZERO_KCAL_MACRO_LIMIT):
        return "zero energy with declared protein or fat"
    predicted = (
        protein * KCAL_PER_G["protein"]
        + fat * KCAL_PER_G["fat"]
        + carbs * KCAL_PER_G["carbs"]
    )
    allowed = max(ATWATER_ABS_FLOOR, ATWATER_REL_TOLERANCE * kcal)
    if abs(kcal - predicted) > allowed:

        return "fails Atwater cross-check (energy vs macros)"
    return None

def basic_rank(name: str, source: str, nova: int | None) -> int:
    lowered = name.lower()
    markers = _MARKER_RE_BY_SOURCE.get(source, _DERIVATIVE_MARKER_RE)
    if markers.search(lowered) or _DERIVATIVE_COMPOUND_RE.search(lowered):
        return BASIC_RANK_DERIVATIVE
    if markers is not _DERIVATIVE_MARKER_RE and _DERIVATIVE_MARKER_RE.search(lowered):

        return BASIC_RANK_ORDINARY
    if _WHOLE_FOOD_RE.search(lowered):
        return BASIC_RANK_WHOLE
    if source == "off" and nova is not None:
        if nova == 1:
            return BASIC_RANK_WHOLE
        if nova == 4:
            return BASIC_RANK_DERIVATIVE
    return BASIC_RANK_ORDINARY

def sane_micro(value: float | None, ceiling: float) -> float | None:

    if value is None or value < 0 or value > ceiling:
        return None
    return value

def clean_micros(food: Food) -> None:
    food.fiber = sane_micro(food.fiber, MAX_MACRO_G)
    food.sugar = sane_micro(food.sugar, MAX_MACRO_G)
    food.sat_fat = sane_micro(food.sat_fat, MAX_MACRO_G)
    food.sodium = sane_micro(food.sodium, 45.0)

    if food.sugar is not None and food.sugar > food.carbs + 1:
        food.sugar = None
    if food.sat_fat is not None and food.sat_fat > food.fat + 1:
        food.sat_fat = None
    if food.fiber is not None and food.fiber > food.carbs + 100:
        food.fiber = None

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
    return 100

def clean_servings(servings: list[tuple[str, float]]) -> list[tuple[str, float]]:

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

    score = 40
    if food.brand:
        score += 10
    if food.servings:
        score += 15
    if food.barcode:
        score += 5
    if food.source in GENERIC_SOURCES:

        score += 20
    if any(v is not None for v in (food.fiber, food.sugar, food.sat_fat, food.sodium)):
        score += 5
    if len(food.name) >= 8:
        score += 5
    return min(score, 100)

BRITISH_TERMS: tuple[tuple[str, str], ...] = (
    ("all-purpose", "plain"),

    ("applesauce", "apple sauce"),
    ("arugula", "rocket"),
    ("baking soda", "bicarbonate of soda"),
    ("beet", "beetroot"),
    ("beets", "beetroots"),
    ("broiled", "grilled"),
    ("catsup", "ketchup"),
    ("cilantro", "coriander"),
    ("corn, sweet", "sweetcorn"),

    ("cornstarch", "cornflour"),
    ("eggplant", "aubergine"),
    ("fava", "broad"),
    ("frosting", "icing"),
    ("garbanzo", "chickpea"),
    ("gelatin", "gelatine"),

    ("ground(?=,)", "minced"),
    ("ground beef", "minced beef"),
    ("ground lamb", "minced lamb"),
    ("lima", "butter"),
    ("molasses", "treacle"),
    ("navy", "haricot"),
    ("romaine", "cos"),
    ("rutabaga", "swede"),
    ("rutabagas", "swedes"),
    ("scallion", "spring onion"),
    ("scallions", "spring onions"),
    ("self-rising", "self-raising"),
    ("shrimp", "prawn"),
    ("shrimps", "prawns"),
    ("skim", "skimmed"),
    ("snow pea", "mangetout"),
    ("Sugars, powdered", "Icing sugar"),
    ("whole wheat", "wholemeal"),
    ("whole-wheat", "wholemeal"),
    ("yogurt", "yoghurt"),
    ("zucchini", "courgette"),
)

_BRITISH_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = tuple(
    (re.compile(r"\b" + us + r"\b", re.IGNORECASE), gb) for us, gb in BRITISH_TERMS
)

def british_name(name: str) -> str | None:

    lower = name.lower()
    result = name
    for pattern, gb in _BRITISH_PATTERNS:
        if not pattern.search(name):
            continue

        if all(w in lower for w in gb.lower().split()):
            continue

        def repl(m: re.Match[str]) -> str:

            return gb[0].upper() + gb[1:] if m.group(0)[0].isupper() else gb

        result = pattern.sub(repl, result)
    out = clean_name(result)
    if out is None or out == name:
        return None
    return out

CIQUAL_CONST = {
    "328": "kcal",
    "333": "kcal_alt",
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

        fr_name = french_names.get(code)
        foods.append(
            Food(
                id=fid, name=name, brand=None, barcode=None, lang="en",
                kcal=kcal, protein=v["protein"], fat=v["fat"], carbs=v["carbs"],

                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),

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

USDA_NUTRIENTS = {
    "1008": "kcal", "1003": "protein", "1004": "fat", "1005": "carbs",
    "1079": "fiber", "2000": "sugar", "1258": "sat_fat", "1093": "sodium",
}

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

                sodium = v.get("sodium")
                foods.append(
                    Food(
                        id=USDA_ID_BASE + int(fid), name=name, brand=None, barcode=None,
                        lang="en",
                        kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],

                        carbs_convention="by_difference",
                        fiber=v.get("fiber"), sugar=v.get("sugar"),
                        sat_fat=v.get("sat_fat"),
                        sodium=sodium / 1000.0 if sodium is not None else None,
                        source="usda",
                        servings=clean_servings(portions.get(fid, [])),
                    )
                )
    return foods

BLS_ID_BASE = 300_000_000

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

    if raw is None:
        return None
    s = raw.strip()
    if s == "TR":
        return 0.0
    return to_float(s)

def load_bls(path: str, rej: Rejections) -> list[Food]:

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

            foods.append(
                Food(
                    id=fid, name=name, brand=None, barcode=None, lang="en",
                    kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],

                    carbs_convention="available",
                    fiber=v.get("fiber"), sugar=v.get("sugar"),
                    sat_fat=v.get("sat_fat"),

                    sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                    source="bls",

                    aliases=[("de", de_name)] if de_name and de_name != name else [],
                )
            )
        return foods

BEDCA_ID_BASE = 100_000_000_000

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

    v = to_float(raw)
    if v is None:
        return None
    if code == "NA":

        if basis == "WKG":
            return v / 10000.0
        if basis != "W":
            return None
        return v / 1000.0
    if basis != "W":
        return None
    if code == "ENERC":
        return v / 4.184 if unit == "kJ" else v

    return v / 1000.0 if unit == "mg" else v

def load_bedca(path: str, rej: Rejections) -> list[Food]:

    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

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
    with open(path, encoding="utf-8-sig", newline="") as fh:
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

            foods.append(
                Food(
                    id=BEDCA_ID_BASE + fid, name=name, brand=None, barcode=None, lang="en",
                    kcal=v["kcal"], protein=v["protein"], fat=v["fat"], carbs=v["carbs"],

                    carbs_convention="available",
                    fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],

                    sodium=v["sodium"],
                    source="bedca",

                    aliases=[("es", es_name)] if es_name and es_name != name else [],
                )
            )
    if wkg_sodium or dropped_basis:
        print(f"      moex: {wkg_sodium} per-kg sodium values converted, "
              f"{dropped_basis} values dropped for an off-basis moex")
    return foods

COFID_ID_BASE = 110_000_000_000

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

    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

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

                carbs_convention="available_monosaccharide",
                fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],

                sodium=(v["sodium"] / 1000.0) if v["sodium"] is not None else None,
                source="cofid",

                aliases=[],
            )
        )
    if paren:
        print(f"      cofid: {paren} parenthesised (estimated) values accepted as numbers")
    return foods

MATVARE_ID_BASE = 120_000_000_000
LIVSMEDEL_ID_BASE = 130_000_000_000
FINELI_ID_BASE = 140_000_000_000
FRIDA_ID_BASE = 150_000_000_000

MATVARE_COLUMNS = {
    "Protein": "protein",
    "Fett": "fat",
    "Karbo": "carbs",
    "Sukker": "sugar",
    "Fiber": "fiber",
    "Mettet": "sat_fat",
    "Na": "sodium",
}

_MATVARE_ID_RE = re.compile(r"^\d{2}\.\d{3}$")

def _matvare_value(raw_quantity: float | int | None, unit: str | None) -> float | None:

    if raw_quantity is None:
        return None
    v = to_float(str(raw_quantity))
    if v is None:
        return None
    return v / 1000.0 if unit == "mg" else v

def load_matvaretabellen(directory: str, rej: Rejections) -> list[Food]:

    en_path = os.path.join(directory, "foods_en.json")
    nb_path = os.path.join(directory, "foods_nb.json")
    if not (os.path.exists(en_path) and os.path.exists(nb_path)):
        print(f"  ! foods_en.json / foods_nb.json not found in {directory}, skipping")
        return []

    en = json.load(open(en_path, encoding="utf-8"))["foods"]
    nb = json.load(open(nb_path, encoding="utf-8"))["foods"]

    en_ids = {f["foodId"].strip(): f for f in en}
    nb_by_id = {f["foodId"].strip(): f for f in nb}
    if set(en_ids) != set(nb_by_id):
        print(f"  ! foods_en.json and foods_nb.json id sets differ, skipping")
        return []
    if len(en_ids) != len(en) or len(nb_by_id) != len(nb):

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

        kcal = f.get("calories", {}).get("quantity")
        reason = passes_quality(kcal, v["protein"], v["fat"], v["carbs"])
        if reason:
            rej.reject(reason)
            continue
        foods.append(
            Food(
                id=fid, name=name, brand=None, barcode=None, lang="en",
                kcal=kcal, protein=v["protein"], fat=v["fat"], carbs=v["carbs"],

                carbs_convention="available",
                fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],
                sodium=v["sodium"],
                source="matvaretabellen",

                aliases=[("nb", nb_name)] if nb_name and nb_name != name else [],
            )
        )
    return foods

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

    sv_path = os.path.join(directory, "foods_sprak1.json")
    en_path = os.path.join(directory, "foods_sprak2.json")
    nv_path = os.path.join(directory, "naringsvarden.jsonl")
    if not (os.path.exists(sv_path) and os.path.exists(en_path) and os.path.exists(nv_path)):
        print(f"  ! foods_sprak1/2.json or naringsvarden.jsonl not found in {directory}, skipping")
        return []

    sv = json.load(open(sv_path, encoding="utf-8"))["livsmedel"]
    en = json.load(open(en_path, encoding="utf-8"))["livsmedel"]

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

            if code == "ENERC" and n.get("enhet") != "kcal":
                continue
            raw = str(n.get("varde")) if n.get("varde") is not None else None
            value = _bedca_value(raw, n.get("enhet") or "", n.get("matrisenhetkod") or "", code)
            if value is None and to_float(raw) is not None:
                dropped_basis += 1
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

FINELI_COMPONENTS: dict[str, tuple[str, str]] = {
    "ENERC": ("kcal", "KJ"),
    "PROT": ("protein", "G"),
    "FAT": ("fat", "G"),
    "CHOAVL": ("carbs", "G"),
    "FIBC": ("fiber", "G"),
    "SUGAR": ("sugar", "G"),
    "FASAT": ("sat_fat", "G"),
    "NA": ("sodium", "MG"),
}

_FINELI_WORD_RE = re.compile(r"[A-Za-zÀ-ɏ][A-Za-zÀ-ɏ'’-]*")

_FINELI_PROPER: dict[str, str] = {

    "french": "French", "greek": "Greek", "turkish": "Turkish",
    "italian": "Italian", "chinese": "Chinese", "finnish": "Finnish",
    "swiss": "Swiss", "indian": "Indian", "thai": "Thai",
    "japanese": "Japanese", "karelian": "Karelian",

    "jansson's": "Jansson's", "john's": "John's", "runeberg's": "Runeberg's",
    "lindstrom's": "Lindstrom's", "lindström's": "Lindström's",

    "kellogg": "Kellogg", "kellogg's": "Kellogg's", "all-bran": "All-Bran",
    "becel": "Becel", "flora": "Flora", "nestle": "Nestle",
    "weetabix": "Weetabix", "semper": "Semper", "cheerios": "Cheerios",
    "quaker": "Quaker", "whopper": "Whopper", "powerade": "Powerade",
    "gatorade": "Gatorade", "schär": "Schär", "twix": "Twix",
    "snickers": "Snickers", "marie": "Marie", "mac": "Mac", "m's": "M's",
    "uncle": "Uncle",
}

_FINELI_VITAMIN_RE = re.compile(r"\b(vitamin )([a-ek])\b")

def _fineli_case(raw: str) -> str:

    if not raw or not raw.isupper():
        return raw
    text = _FINELI_WORD_RE.sub(
        lambda m: _FINELI_PROPER.get(m.group(0).lower(), m.group(0).lower()), raw
    )
    text = _FINELI_VITAMIN_RE.sub(lambda m: m.group(1) + m.group(2).upper(), text)

    for i, ch in enumerate(text):
        if ch.isalpha():
            return text[:i] + ch.upper() + text[i + 1:]
    return text

def load_fineli(directory: str, rej: Rejections) -> list[Food]:

    food_path = os.path.join(directory, "food.csv")
    if not os.path.exists(food_path):
        print(f"  ! food.csv not found in {directory}, skipping")
        return []

    def rows(name: str) -> list[dict[str, str]]:
        with open(os.path.join(directory, name), encoding="cp1252", newline="") as fh:
            return list(csv.DictReader(fh, delimiter=";"))

    en_names = {r["FOODID"]: r["FOODNAME"] for r in rows("foodname_EN.csv")}
    fi_names = {r["FOODID"]: r["FOODNAME"] for r in rows("foodname_FI.csv")}
    sv_names = {r["FOODID"]: r["FOODNAME"] for r in rows("foodname_SV.csv")}
    food_ids = {r["FOODID"] for r in rows("food.csv")}
    if not (set(en_names) == set(fi_names) == set(sv_names) == food_ids):
        print(f"  ! Fineli food.csv and name files do not carry the same FOODIDs, skipping")
        return []

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
            v = v / 4.184
        elif code == "NA":
            v = v / 1000.0
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

                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"), sat_fat=v.get("sat_fat"),
                sodium=v.get("sodium"),
                source="fineli",
                aliases=aliases,
            )
        )
    return foods

FRIDA_COLUMNS = {
    "KCAL": "kcal",
    "PROTEIN": "protein",
    "FAT": "fat",

    "SAT_FAT": "sat_fat",
    "CARBS": "carbs",
    "FIBRE": "fiber",
    "SUGAR": "sugar",
    "SODIUM": "sodium",
}

def load_frida(path: str, rej: Rejections) -> list[Food]:

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

                    carbs_convention="available",
                    fiber=v["fiber"], sugar=v["sugar"],
                    sat_fat=v["sat_fat"],

                    sodium=(v["sodium"] / 1000.0) if v["sodium"] is not None else None,
                    source="frida",
                    aliases=[("da", da_name)] if da_name and da_name != name else [],
                )
            )
    return foods

SWISS_ID_BASE = 160_000_000_000

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

    if raw is None:
        return None
    s = raw.strip()
    if s in ("", "n.d."):
        return None
    if s == "tr.":
        return 0.0
    return to_float(s)

def load_swiss(path: str, rej: Rejections) -> list[Food]:

    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

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

                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),

                sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                source="swiss",
                aliases=langs,
            )
        )
    for lang in ("de", "fr", "it"):
        a, loan, unusable = alias_counts[lang]
        print(f"      aliases: {lang} {a} attached ({loan} loanword, {unusable} unusable)")
    return foods

TCA_ID_BASE = 170_000_000_000

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

TCA_NAME_LANG = "pt"

TCA_ALCOHOL_GROUP = "Bebidas alcoólicas"

def load_tca(path: str, rej: Rejections) -> list[Food]:

    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))

    foods: list[Food] = []
    for r in rows:
        rej.seen += 1
        if (r["NIVEL1"] or "").strip() == TCA_ALCOHOL_GROUP:

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

                carbs_convention="available",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),

                sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                source="tca",

                aliases=[],
            )
        )
    return foods

CNF_ID_BASE = 180_000_000_000

CNF_NUTRIENTS = {
    "208": "kcal", "203": "protein", "204": "fat", "205": "carbs",
    "291": "fiber", "269": "sugar", "606": "sat_fat", "307": "sodium",
}

def load_cnf(path: str, rej: Rejections) -> list[Food]:

    if not os.path.exists(path):
        print(f"  ! {path} not found, skipping")
        return []

    with zipfile.ZipFile(path) as z:

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

                carbs_convention="by_difference",
                fiber=v.get("fiber"), sugar=v.get("sugar"),
                sat_fat=v.get("sat_fat"),

                sodium=(v["sodium"] / 1000.0) if v.get("sodium") is not None else None,
                source="cnf",

                aliases=[("fr", fr)] if fr and fr != name else [],
            )
        )
    return foods

AFCD_ID_BASE = 190_000_000_000

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

                carbs_convention="available",
                fiber=v["fiber"], sugar=v["sugar"], sat_fat=v["sat_fat"],

                sodium=(v["sodium"] / 1000.0) if v["sodium"] is not None else None,
                source="afcd",

                aliases=[],
            )
        )
    return foods

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

GENERIC_SOURCE_COUNTRY: dict[str, str] = {
    "usda": "US", "ciqual": "FR", "bls": "DE", "bedca": "ES",

    "cofid": "UK",
    "matvaretabellen": "NO", "livsmedel": "SE", "fineli": "FI", "frida": "DK",
    "swiss": "CH", "tca": "PT",
    "cnf": "CA",
    "afcd": "AU",
}

GENERIC_NATIVE_NAME_LANG: dict[str, str] = {
    "ciqual": "fr", "bls": "de", "bedca": "es",
    "matvaretabellen": "nb", "livsmedel": "sv", "fineli": "fi", "frida": "da",
}

SWISS_NAME_LANG = "de"

COMPOUND_LANGS = {"da", "nb", "sv", "de", "fi"}

COMPOUND_MIN_HEAD = 4

COMPOUND_MIN_COUNT = 2
COMPOUND_LEX_LEN = (3, 12)

COMPOUND_LINKS = ("", "s", "es", "n", "en", "e", "er")

_COMPOUND_TOKEN = re.compile(r"[0-9a-z]+")

LEX_SEP = "\t"

def lexicon_from_names(names: list[str]) -> set[str]:

    lo, hi = COMPOUND_LEX_LEN
    counts: dict[str, int] = {}
    for name in names:
        for token in _COMPOUND_TOKEN.findall(fold_name(name)):
            counts[token] = counts.get(token, 0) + 1
    return {t for t, n in counts.items() if lo <= len(t) <= hi and n >= COMPOUND_MIN_COUNT}

def split_compounds(generic: list[Food]) -> dict[str, tuple[int, int]]:

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

                f.search_extra = " ".join(sorted(found))
        report[lang] = (helped, len(emitted))
    return report

def compound_lexicons(generic: list[Food]) -> dict[tuple[str, str], set[str]]:

    by_source: dict[tuple[str, str], list[str]] = {}
    for f in generic:
        if f.lang:
            by_source.setdefault((f.source, f.lang), []).append(f.name)
    return {key: lexicon_from_names(names) for key, names in by_source.items()}

def lexicon_lines(lexicons: dict[tuple[str, str], set[str]]) -> list[str]:

    return [
        LEX_SEP.join((source, lang, word)) + "\n"
        for source, lang in sorted(lexicons)
        for word in sorted(lexicons[(source, lang)])
    ]

def write_compound_lexicon(
    lexicons: dict[tuple[str, str], set[str]], path: str,
) -> tuple[int, int]:

    lines = lexicon_lines(lexicons)
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.writelines(lines)
    return len(lexicons), len(lines)

def promote_native_names(generic: list[Food]) -> dict[str, tuple[int, int]]:

    moved: dict[str, tuple[int, int]] = {}
    for f in generic:
        target = SWISS_NAME_LANG if f.source == "swiss" else GENERIC_NATIVE_NAME_LANG.get(f.source)
        if target is None:
            continue
        promoted, already = moved.get(f.source, (0, 0))
        native = next((n for lang, n in f.aliases if lang == target), None)
        if native is None:

            f.lang = target
            moved[f.source] = (promoted, already + 1)
            continue
        english = f.name
        f.name = native
        f.lang = target

        f.aliases = [(lang, n) for lang, n in f.aliases if lang not in ("en", target)]
        if english and english != native:

            f.aliases.insert(0, ("en", english))

        f.aliases = [(lang, n) for lang, n in f.aliases if n != f.name]
        moved[f.source] = (promoted + 1, already)
    return moved

SUPRANATIONAL_CODES = {"world"}

def slug(name: str) -> str:

    s = "".join(
        ch for ch in unicodedata.normalize("NFKD", name.lower())
        if unicodedata.category(ch) != "Mn"
    )
    s = s.replace("'", "-").replace("’", "-")
    s = "".join(c if c.isalnum() else "-" for c in s)
    return re.sub("-+", "-", s).strip("-")

def parse_countries_tags(raw: str | None) -> tuple[list[str], list[str]]:

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

    codes, _unresolved = parse_countries_tags(raw)
    if not codes or codes[0] in SUPRANATIONAL_CODES:
        return None
    return codes[0]

OFF_COLUMNS = (
    "code", "product_name", "brands",
    "energy-kcal_100g",
    "proteins_100g", "fat_100g", "carbohydrates_100g",
    "fiber_100g", "sugars_100g", "saturated-fat_100g", "sodium_100g",
    "serving_size", "serving_quantity", "unique_scans_n",
    "nova_group", "data_quality_errors_tags", "countries_tags",
)

OFF_ERROR_SUBSTRINGS = ("energy-value", "value-total-over", "value-over-105", "greater-than")

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

    text = (serving_size or "").strip()
    if _VOLUME_SERVING_RE.search(text):
        return None
    match = _MASS_SERVING_RE.search(text)
    if match:
        value = to_float(match.group(1))
        factor = _MASS_FACTORS.get(match.group(2).lower())
        if value is not None and factor:
            return value * factor

    return serving_quantity if not text else None

def load_off(path: str, rej: Rejections) -> Iterator[Food]:

    with gzip.open(path, "rt", encoding="utf-8", errors="replace", newline="") as fh:
        reader = csv.reader(fh, delimiter="\t")
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

                servings.append((CANONICAL_SERVING, serving_g))

            brand = (col(row, "brands") or "").strip()[:80] or None

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

def create_schema(db: sqlite3.Connection, schema_path: str) -> str:

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

    parts = []

    for label, value in (("off", args.off), ("ciqual", args.ciqual), ("bls", args.bls),
                         ("bedca", args.bedca), ("cofid", args.cofid),
                         ("matvaretabellen", args.matvaretabellen),
                         ("livsmedel", args.livsmedel), ("fineli", args.fineli),
                         ("frida", args.frida), ("swiss", args.swiss),
                         ("tca", args.tca), ("cnf", args.cnf),
                         ("afcd", args.afcd)):
        if value:

            name = os.path.basename(os.path.normpath(str(value)))
            parts.append(f"{label}:{name}")
    for path in args.usda or []:
        parts.append(f"usda:{os.path.basename(path)}")
    return ",".join(parts)

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
    if os.path.dirname(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path):
        os.remove(path)

    db = sqlite3.connect(path)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    identity_hash = create_schema(db, schema_path)

    db.execute(f"PRAGMA application_id = {int(version)}")

    db.executemany(
        "INSERT INTO foods (id, name, name_folded, lang, brand, barcode, kcal_100g,"
        " protein_100g, fat_100g, carbs_100g, carbs_convention, fiber_100g, sugar_100g,"
        " sat_fat_100g, sodium_100g, density_g_per_ml, source, quality_score, basic_rank,"
        " source_rank, alias_names, country, search_extra)"
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (
            (f.id, f.name, fold_name(f.name), f.lang, f.brand, f.barcode, f.kcal, f.protein,
             f.fat, f.carbs, f.carbs_convention, f.fiber, f.sugar, f.sat_fat, f.sodium,
             f.density, f.source, f.quality, f.basic, source_rank(f.source),

             (" ".join(fold_name(name) for _, name in f.aliases) or None),
             f.country,

             f.search_extra)
            for f in foods
        ),
    )
    db.executemany(
        "INSERT INTO food_aliases (food_id, lang, name) VALUES (?,?,?)",
        ((f.id, lang, name) for f in foods for lang, name in f.aliases),
    )

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

    db.execute("CREATE TABLE food_search_members (food_id INTEGER PRIMARY KEY, preferred INTEGER NOT NULL)")
    db.executemany("INSERT INTO food_search_members VALUES (?, ?)",
                   ((i, int(i in preferred_search_ids)) for i in sorted(search_ids)))
    db.execute(
        "CREATE VIEW food_search_content AS"
        " SELECT f.id, f.name_folded, f.brand, f.alias_names, f.search_extra"
        " FROM foods f JOIN food_search_members m ON m.food_id = f.id"
    )

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

    ok &= audit(path)
    return ok

def display_key(food: Food) -> tuple[str, str, int]:

    return (food.name.strip().lower(), (food.brand or "").strip().lower(), int(food.kcal // 50))

@dataclass
class DisplayGroup:
    winner: Food
    members: list[Food]

def group_display_duplicates(foods: list[Food]) -> list[DisplayGroup]:

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

    sources: dict[str, list[Food]] = {}
    for food in foods:
        sources.setdefault(food.source, []).append(food)
    return [winner for rows in sources.values() for winner in established_generic_display_rows(rows)]

def prepare_generic_catalogue(foods: list[Food]) -> tuple[list[Food], set[int]]:

    established = established_generic_display_rows(copy.deepcopy(foods))
    promote_native_names(established)
    established = established_generic_display_rows(established)
    preferred_ids = {food.id for food in established}
    search = generic_display_rows(foods)
    promote_native_names(foods)
    search = generic_display_rows(search)
    return search, preferred_ids

def branded_display_groups(foods: list[Food], min_scans: int) -> list[DisplayGroup]:

    groups = group_display_duplicates(foods)
    groups.sort(key=lambda g: (g.winner.popularity, g.winner.quality), reverse=True)

    kept = [g for g in groups if g.winner.popularity >= min_scans]
    print(f"\n  scan floor: dropped {len(groups) - len(kept):,} display groups, "
          f"{len(kept):,} remain")
    return kept

def select_catalogue(
    generic: list[Food], generic_search: list[Food], groups: list[DisplayGroup], limit: int,
) -> tuple[list[Food], set[int]]:

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

    if args.stub:

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

    requested = {
        "usda": bool(args.usda), "ciqual": args.ciqual, "bls": args.bls,
        "bedca": args.bedca, "cofid": args.cofid,
        "matvaretabellen": args.matvaretabellen, "livsmedel": args.livsmedel,
        "fineli": args.fineli, "frida": args.frida, "swiss": args.swiss,
        "tca": args.tca, "cnf": args.cnf, "afcd": args.afcd,
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
        if f.source == "tca":
            continue
        gb = british_name(f.name)
        if gb:
            f.aliases.append(("en-GB", gb))

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
        clean_micros(f)
        f.basic = basic_rank(f.name, f.source, f.nova)

    generic_search, preferred_generic_ids = prepare_generic_catalogue(generic)

    for lang, (helped, distinct) in sorted(split_compounds(generic_search).items()):
        print(f"      compound heads: {lang} {helped} rows, {distinct} distinct heads")

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
