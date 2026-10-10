"""Strict selected-field NEVO reader. Raw figures and references are never repaired or summed."""
from __future__ import annotations

import csv
import re
from collections import defaultdict
from pathlib import Path

from nutrition_values import parse_nutrient

VERSION = "NEVO-Online 2025 9.0"
# Reserved band [200e9, 210e9), between AFCD and OFF. The publisher ID remains reversible.
ID_BASE = 200_000_000_000
SELECTED = {
    "ENERCC": ("kcal", "CALORIES", "kcal"),
    "PROT": ("g", "PROTEIN", "protein"),
    "FAT": ("g", "FAT", "fat"),
    "CHO": ("g", "CARBS", "carbs"),
    "FIBT": ("g", "FIBRE", "fiber"),
    "SUGAR": ("g", "SUGAR", "sugar"),
    "FASAT": ("g", "SATURATED_FAT", "sat_fat"),
    "NA": ("mg", "SODIUM", "sodium"),
}
KEY = "NEVO-code"
NL = "Voedingsmiddelnaam/Dutch food name"
EN = "Engelse naam/Food name"
BASIS = "Hoeveelheid/Quantity"
RELEASE = "NEVO-versie/NEVO-version"
TRACE = "Bevat sporen van/Contains traces of"
FORTIFIED = "Is verrijkt met/Is fortified with"
DETAIL_FLAG = "Spoor / Verrijkt/Trace / Fortified"


def _read(path: Path, required: set[str]) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="|", strict=True)
        headers = reader.fieldnames or []
        if len(headers) != len(set(headers)) or required - set(headers):
            raise ValueError(f"{path.name}: missing or duplicate required headers: {sorted(required - set(headers))}")
        rows = list(reader)
    for line, row in enumerate(rows, 2):
        if None in row or any(value is None for value in row.values()):
            raise ValueError(f"{path.name}:{line}: wrong column count")
    return rows


def read_nevo(path: str) -> list[dict]:
    """Return Food kwargs only after reconciling wide values with definitions and all details.

    Any malformed/conflicting input fails the entire requested import before output replacement.
    Repeated detail rows may differ in provenance only. Fortification is source annotation, not
    a detection limit or an uncertainty qualifier. No serving mass or density is inferred.
    """
    path = Path(path)
    definitions = _read(path.with_name(path.stem + "_Nutrienten_Nutrients.csv"),
                        {"Nutrient-code", "Eenheid/Unit"})
    units = {}
    for row in definitions:
        code, unit = row["Nutrient-code"], row["Eenheid/Unit"]
        if code in units and units[code] != unit:
            raise ValueError(f"NEVO conflicting definition for {code}")
        units[code] = unit
    for code, (unit, _, _) in SELECTED.items():
        if units.get(code) != unit:
            raise ValueError(f"NEVO missing/wrong selected definition: {code}")

    wide = _read(path, {KEY, NL, EN, BASIS, RELEASE, TRACE, FORTIFIED, "Synoniem"} |
                 {f"{code} ({unit})" for code, (unit, _, _) in SELECTED.items()})
    foods = {}
    flags = {}
    for row in wide:
        code = row[KEY]
        if not re.fullmatch(r"[1-9][0-9]{0,9}", code) or int(code) >= 10_000_000_000:
            raise ValueError(f"NEVO invalid publisher food ID: {code}")
        if code in foods:
            raise ValueError(f"NEVO duplicate food ID: {code}")
        if row[RELEASE] != VERSION or row[BASIS] not in {"per 100g", "per 100ml"}:
            raise ValueError(f"NEVO unsupported version/basis for {code}")
        if not row[NL].strip() or not row[EN].strip():
            raise ValueError(f"NEVO missing bilingual name for {code}")
        trace = set(filter(None, row[TRACE].split(", ")))
        fortified = set(filter(None, row[FORTIFIED].split(", ")))
        if (trace | fortified) - units.keys() or trace & fortified:
            raise ValueError(f"NEVO unsupported/conflicting annotation for {code}")
        flags[code] = {nutrient: "TR" if nutrient in trace else "+" if nutrient in fortified else ""
                       for nutrient in SELECTED}
        foods[code] = row

    details = _read(path.with_name(path.stem + "_Details.csv"),
                    {KEY, NL, EN, BASIS, RELEASE, "Nutrient-code", "Gehalte/Value",
                     "Eenheid/Unit", DETAIL_FLAG, "Broncode/Source code", "Referentie/Reference"})
    refs = defaultdict(list)
    seen = set()
    for row in details:
        code, nutrient = row[KEY], row["Nutrient-code"]
        if code not in foods or nutrient not in units:
            raise ValueError(f"NEVO unknown food/nutrient in details: {code}/{nutrient}")
        if nutrient not in SELECTED:
            continue
        wide_row = foods[code]
        unit = SELECTED[nutrient][0]
        expected = [wide_row[k] for k in (RELEASE, NL, EN, BASIS)] + [
            wide_row[f"{nutrient} ({unit})"], unit, flags[code][nutrient]]
        actual = [row[k] for k in (RELEASE, NL, EN, BASIS)] + [
            row["Gehalte/Value"], row["Eenheid/Unit"], row[DETAIL_FLAG]]
        if actual != expected:
            raise ValueError(f"NEVO conflicting wide/detail figures or annotations: {code}/{nutrient}")
        key = code, nutrient
        seen.add(key)
        reference = {"code": row["Broncode/Source code"], "reference": row["Referentie/Reference"]}
        if reference not in refs[key]:
            refs[key].append(reference)

    result = []
    for code in sorted(foods, key=int):
        row = foods[code]
        metadata = {}
        numbers = {}
        for nutrient, (unit, kind, field) in SELECTED.items():
            raw = row[f"{nutrient} ({unit})"]
            # The publisher omits Details rows for missing optional values.
            if raw and (code, nutrient) not in seen:
                raise ValueError(f"NEVO missing selected detail: {code}/{nutrient}")
            value = parse_nutrient(raw, unit, kind, flags[code][nutrient])
            value["sourceOrigin"] = dict(dataset="nevo", version=VERSION, foodId=code,
                nutrientCode=nutrient, references=sorted(refs[code, nutrient], key=lambda r: (r["code"], r["reference"])))
            metadata[kind] = value
            numbers[field] = value.get("value")
        if any(numbers[k] is None for k in ("kcal", "protein", "fat", "carbs")):
            raise ValueError(f"NEVO missing primary composition: {code}")
        # Structural sanity only: source energy includes fibre, polyols, alcohol and acids.
        if not 0 <= numbers["kcal"] <= 900 or any(
                numbers[k] is not None and not 0 <= numbers[k] <= limit for k, limit in (
                    ("protein", 100), ("fat", 100), ("carbs", 100), ("fiber", 100),
                    ("sugar", 100), ("sat_fat", 100), ("sodium", 45))) or sum(
                numbers[k] for k in ("protein", "fat", "carbs")) > 105:
            raise ValueError(f"NEVO composition outside supported sane range: {code}")
        if (numbers["sugar"] is not None and numbers["sugar"] > numbers["carbs"] + 1 or
                numbers["sat_fat"] is not None and numbers["sat_fat"] > numbers["fat"] + 1 or
                numbers["kcal"] == 0 and (numbers["protein"] > .5 or numbers["fat"] > .5)):
            raise ValueError(f"NEVO conflicting component/energy figures: {code}")
        # An identical bilingual name is already stored verbatim in foods.name. The existing
        # audit forbids redundant aliases; do not duplicate its tokens or display text.
        aliases = [("en", row[EN])] if row[EN] != row[NL] else []
        # Preserve the complete published synonym cell; splitting slash alternatives can
        # detach cooking/type qualifiers from their subject.
        if row["Synoniem"] and row["Synoniem"] != row[NL]:
            aliases.append(("nl", row["Synoniem"]))
        result.append(dict(id=ID_BASE + int(code), name=row[NL], brand=None, barcode=None,
            lang="nl", source="nevo", country="NL", carbs_convention="available",
            aliases=aliases, nutrition_basis="ml" if row[BASIS] == "per 100ml" else "g",
            nutrition_metadata=metadata, **numbers))
    return result
