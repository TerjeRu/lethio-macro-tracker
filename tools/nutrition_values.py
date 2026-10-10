"""Selected-nutrient contract shared with NutritionCodec; no source loader or estimates."""
from __future__ import annotations

import json
import math
import re

NUTRIENTS = {"CALORIES", "PROTEIN", "FAT", "CARBS", "FIBRE", "SUGAR", "SATURATED_FAT", "SODIUM"}
QUALIFIERS = {"TRACE", "BELOW_DETECTION", "BELOW_QUANTIFICATION",
              "BELOW_DETECTION_OR_QUANTIFICATION", "LOGICAL_ZERO", "UNDECIDABLE",
              "UNSPECIFIED", "BOUNDED", "BEST_ESTIMATE", "AVERAGE", "WEIGHTED"}
VALUE_TYPES = {"": None, "TR": "TRACE", "trace": "TRACE", "BL": "BELOW_DETECTION",
               "BQ": "BELOW_QUANTIFICATION", "BLX": "BELOW_DETECTION_OR_QUANTIFICATION",
               "LZ": "LOGICAL_ZERO", "UD": "UNDECIDABLE", "BE": "BEST_ESTIMATE",
               "AV": "AVERAGE", "W": "WEIGHTED", "X": "UNSPECIFIED", "+": None}
NUMBER = re.compile(r"\d+(?:[.,]\d+)?|[.,]\d+")


def parse_nutrient(raw: str | None, unit: str, nutrient: str, qualifier: str = "") -> dict:
    """Keep source notation, normalize nutrient units explicitly, and preserve supplied bounds.

    A separate BL flag accompanying numeric zero provides NO numeric detection limit.
    Textual trace without a number remains non-numeric. UD does not become a known zero.
    """
    if nutrient not in NUTRIENTS or qualifier not in VALUE_TYPES:
        raise ValueError("Unknown selected nutrient or value type")
    allowed = {"kcal": 1.0} if nutrient == "CALORIES" else {"g": 1.0, "mg": 0.001}
    if unit not in allowed:
        raise ValueError("Wrong selected-nutrient unit")
    scale = allowed[unit]
    result = {"sourceUnit": unit}
    if qualifier:
        result["sourceValueType"] = qualifier
    if raw is not None:
        result["sourceValue"] = raw
    kind = VALUE_TYPES[qualifier]
    kinds = {kind} if kind else set()
    text = (raw or "").strip()
    if text.lower() in {"tr", "trace"}:
        kinds.add("TRACE")
        text = ""
    if text:
        marker = text[0] if text[0] in "<>" else None
        inclusive = len(text) > 1 and text[1] == "=" if marker else True
        numeric = text[2 if inclusive else 1:].strip() if marker else text
        if not NUMBER.fullmatch(numeric):
            raise ValueError("Malformed nutrient figure")
        value = float(numeric.replace(",", ".")) * scale
        if not math.isfinite(value) or value < 0:
            raise ValueError("Nonfinite or negative nutrient figure")
        result["value"] = value
        if marker:
            result["upperBound" if marker == "<" else "lowerBound"] = value
            if not inclusive:
                result["upperInclusive" if marker == "<" else "lowerInclusive"] = False
            kinds.add("BOUNDED")
    if kinds:
        result["qualifiers"] = sorted(kinds)
    validate_value(result)
    return result


def validate_value(value: dict) -> None:
    if not isinstance(value, dict) or set(value) - {
        "value", "qualifiers", "incomplete", "lowerBound", "upperBound", "lowerInclusive",
        "upperInclusive", "sourceValue", "sourceUnit", "sourceValueType", "sourceOrigin"
    }:
        raise ValueError("Unknown nutrition metadata fields")
    for key in ("value", "lowerBound", "upperBound"):
        number = value.get(key)
        if number is not None and (isinstance(number, bool) or not isinstance(number, (int, float))
                                   or not math.isfinite(number) or number < 0):
            raise ValueError("Invalid nutrition metadata number")
    kinds = value.get("qualifiers", [])
    if not isinstance(kinds, list) or len(set(kinds)) != len(kinds) or set(kinds) - QUALIFIERS:
        raise ValueError("Invalid nutrition qualification")
    if value.get("incomplete", False) not in (True, False) or not isinstance(value.get("incomplete", False), bool):
        raise ValueError("Invalid completeness flag")
    low, high = value.get("lowerBound"), value.get("upperBound")
    if low is not None and high is not None and low > high:
        raise ValueError("Conflicting nutrient bounds")
    for bound in ("lower", "upper"):
        inclusive = value.get(bound + "Inclusive", True)
        if not isinstance(inclusive, bool) or not inclusive and value.get(bound + "Bound") is None:
            raise ValueError("Invalid nutrient bound inclusivity")
    if "LOGICAL_ZERO" in kinds and value.get("value") != 0:
        raise ValueError("Logical zero with nonzero/missing figure")
    if any(value.get(key) is not None and not isinstance(value[key], str)
           for key in ("sourceValue", "sourceUnit", "sourceValueType")):
        raise ValueError("Invalid source notation")
    origin = value.get("sourceOrigin")
    if origin is not None:
        if not isinstance(origin, dict) or set(origin) != {
                "dataset", "version", "foodId", "nutrientCode", "references"}:
            raise ValueError("Invalid nutrient source origin")
        if any(not isinstance(origin[k], str) or not origin[k] for k in (
                "dataset", "version", "foodId", "nutrientCode")):
            raise ValueError("Missing nutrient source identity")
        if not isinstance(origin["references"], list) or any(
                not isinstance(r, dict) or set(r) != {"code", "reference"} or
                any(not isinstance(v, str) for v in r.values()) for r in origin["references"]):
            raise ValueError("Invalid nutrient source references")


def encode_nutrients(values: dict | None) -> str | None:
    if not values:
        return None
    if set(values) - NUTRIENTS:
        raise ValueError("Unknown selected nutrient")
    for value in values.values():
        validate_value(value)
    return json.dumps({"nutrients": values}, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
