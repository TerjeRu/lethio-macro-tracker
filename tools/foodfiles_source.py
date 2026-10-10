"""Selected FOODfiles 2024 v1 figures and source portions; no inferred composition."""
from __future__ import annotations

import csv
import re
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

from nutrition_values import parse_nutrient

VERSION = "FOODfiles 2024 v1"
ID_BASE = 210_000_000_000
SELECTED = {
    "ENERC_FSANZ2_KCAL": ("CALORIES", "kcal", "kcal"),
    "PROT": ("PROTEIN", "g", "protein"), "FAT": ("FAT", "g", "fat"),
    "CHOAVL_FSANZ": ("CARBS", "g", "carbs"), "FIBTG": ("FIBRE", "g", "fiber"),
    "SUGAR": ("SUGAR", "g", "sugar"), "FASAT": ("SATURATED_FAT", "g", "sat_fat"),
    "NA": ("SODIUM", "mg", "sodium"),
}
QUARANTINE = {
    "B1035": "Nonzero FAT figure has logical-zero value type LZ",
    "C59": "CHOAVL_FSANZ and SUGAR exceed 100 g per 100 g",
    "P10010": "FASAT exceeds FAT by more than the existing 1 g tolerance",
}
PROVENANCE = ("Source Code", "Acquisition Type Code", "Method Type Code", "Method Indicator Code")
# Exact source portions hidden after review; foods and composition remain available.
HIDDEN_PORTIONS = {
    ("P10005", "1 pinch fine"): Decimal("0.32"),
    ("P10007", "1 pinch fine"): Decimal("0.38"),
    **{(fid, "1 teaspoon (5 mL)"): Decimal(mass) for fid, mass in
       (("P24", "0.6"), ("P27", "0.6"), ("P31", "0.6"), ("P40", "0.6"),
        ("P46", "0.3"), ("P54", "0.7"))},
    ("X1014", "1 roll sushi (approximately, 42 g) seaweed wrap"): Decimal("0.95"),
}


def _read(path: Path, required: set[str]) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        copyright_line = next(stream, "")
        if not copyright_line.startswith("© Copyright ") or not copyright_line.rstrip().endswith("2024."):
            raise ValueError(f"{path.name}: unsupported FOODfiles release header")
        reader = csv.DictReader(stream, delimiter="~", strict=True)
        headers = reader.fieldnames or []
        if len(headers) != len(set(headers)) or required - set(headers):
            raise ValueError(f"{path.name}: missing or duplicate required headers")
        rows = list(reader)
    for row in rows:
        if any(v is None for key, v in row.items() if key is not None):
            raise ValueError(f"{path.name}: short row")
        if None in row:
            # This publisher row has one extra separator in its trailing sampling description.
            # Identity/name/alias cells precede it and are intact; no trailing prose is imported.
            if path.name != "Names" or row.get("FoodID") != "M1144" or len(row[None]) != 1:
                raise ValueError(f"{path.name}: unexpected extra column")
    return rows


def food_id(publisher_id: str) -> int:
    if not re.fullmatch(r"[A-Z][1-9][0-9]{0,7}", publisher_id):
        raise ValueError(f"FOODfiles invalid publisher ID: {publisher_id}")
    # Reserved [210e9,220e9) band, reversible letter/number identity independent of row order.
    return ID_BASE + (ord(publisher_id[0]) - ord("A")) * 100_000_000 + int(publisher_id[1:])


def read_foodfiles(path: str, report: dict | None = None) -> list[dict]:
    path = Path(path)
    names = _read(path.with_name("Names"), {"FoodID", "Food Name", "Short Food Name", "AlternativeNames"})
    foods = {}
    for row in names:
        fid = row["FoodID"]
        food_id(fid)
        if fid in foods or not row["Food Name"].strip():
            raise ValueError(f"FOODfiles duplicate ID or missing name: {fid}")
        foods[fid] = row
    definitions = _read(path.with_name("StandardCodes"), {"Code", "Unit Code", "Matrix Unit Code"})
    codes = {}
    for row in definitions:
        if row["Code"] in codes:
            raise ValueError("FOODfiles duplicate component definition")
        codes[row["Code"]] = row
    for code, (_, unit, _) in SELECTED.items():
        definition = codes.get(code, {})
        if definition.get("Unit Code") != unit or definition.get("Matrix Unit Code") != "W":
            raise ValueError(f"FOODfiles missing/wrong selected definition: {code}")
    rows = _read(path, {"FoodID", "Component Identifier", "Value", "Unit Code", "Matrix Unit Code",
                        "Value Type Code", *PROVENANCE})
    matrix = {}
    for row in rows:
        fid, code = row["FoodID"], row["Component Identifier"]
        if fid not in foods or code not in codes:
            raise ValueError(f"FOODfiles orphan/unknown component: {fid}/{code}")
        if code not in SELECTED:
            continue
        key = fid, code
        if key in matrix:
            raise ValueError(f"FOODfiles duplicate selected component: {fid}/{code}")
        matrix[key] = row

    # Scope the reviewed omissions to the actual conflicts reviewed in this release.
    pinned = {"B1035": {"FAT": ("2.6", "LZ")},
              "C59": {"CHOAVL_FSANZ": ("101", None), "SUGAR": ("101", None)},
              "P10010": {"FAT": ("20.4", None), "FASAT": ("21.8", None)}}
    for fid, expected in pinned.items():
        if fid not in foods:
            continue
        for code, (raw, kind) in expected.items():
            row = matrix.get((fid, code), {})
            if row.get("Value") != raw or kind is not None and row.get("Value Type Code") != kind:
                raise ValueError(f"FOODfiles reviewed quarantine has changed: {fid}/{code}")

    portions, densities = defaultdict(list), defaultdict(set)
    omitted_portions = []
    csm = _read(path.with_name("Csm"), {"FoodID", "Food Name", "CSM", "Measure", "Density (g/cm3)"})
    grouped = defaultdict(list)
    for row in csm:
        fid = row["FoodID"]
        if fid not in foods or row["Food Name"] != foods[fid]["Food Name"] or not row["CSM"].strip():
            raise ValueError(f"FOODfiles portion identity/name mismatch: {fid}")
        mass = Decimal(row["Measure"])
        density = Decimal(row["Density (g/cm3)"]) if row["Density (g/cm3)"] else None
        if not mass.is_finite() or mass <= 0 or density is not None and (not density.is_finite() or density <= 0):
            raise ValueError(f"FOODfiles invalid portion/density: {fid}")
        if density is not None:
            densities[fid].add(density)
        grouped[fid, row["CSM"]].append((row, mass, density))
    for (fid, label), group in grouped.items():
        if fid in QUARANTINE:
            continue
        if len(group) > 1:
            if (fid != "M1148" or label != "1 cup (250 mL) diced " or len(group) != 2 or
                    {(mass, density) for _, mass, density in group} !=
                    {(Decimal("200"), Decimal("0.8")), (Decimal("215"), Decimal("0.86"))}):
                raise ValueError(f"FOODfiles unexpected ambiguous portion: {fid}/{label}")
            omitted_portions.append(dict(foodId=fid, label=label, grams="200",
                reason="Published 215 g portion selected; original alternative retained outside catalogue"))
            group = [item for item in group if item[1] == Decimal("215")]
        mass = group[0][1]
        if (fid, label) in HIDDEN_PORTIONS:
            if mass != HIDDEN_PORTIONS[fid, label]:
                raise ValueError(f"FOODfiles reviewed hidden portion has changed: {fid}/{label}")
            omitted_portions.append(dict(foodId=fid, label=label, grams=str(mass),
                reason="Tiny portion hidden; food and published nutrition retained"))
            continue
        if not 1 <= mass <= 2000:
            raise ValueError(f"FOODfiles unreviewed portion outside 1g-2000g: {fid}/{label}")
        portions[fid].append((label, float(mass)))

    result = []
    for fid in sorted(foods):
        if fid in QUARANTINE:
            continue
        metadata, numbers = {}, {}
        for code, (kind, unit, field) in SELECTED.items():
            row = matrix.get((fid, code))
            if row is None and field != "fiber":
                raise ValueError(f"FOODfiles missing selected component: {fid}/{code}")
            if row and (row["Unit Code"] != unit or row["Matrix Unit Code"] != "W"):
                raise ValueError(f"FOODfiles wrong selected unit/basis: {fid}/{code}")
            value = parse_nutrient(row["Value"] if row else None, unit, kind, row["Value Type Code"] if row else "")
            value["sourceOrigin"] = dict(dataset="foodfiles", version=VERSION, foodId=fid, nutrientCode=code,
                references=[dict(code=key, reference=row[key]) for key in PROVENANCE] if row else [])
            metadata[kind], numbers[field] = value, value.get("value")
        if any(numbers[k] is None for k in ("kcal", "protein", "fat", "carbs")):
            raise ValueError(f"FOODfiles missing primary composition: {fid}")
        if (not 0 <= numbers["kcal"] <= 900 or any(numbers[k] is not None and not 0 <= numbers[k] <= limit
            for k, limit in (("protein",100),("fat",100),("carbs",100),("fiber",100),
                             ("sugar",100),("sat_fat",100),("sodium",45))) or
            sum(numbers[k] for k in ("protein","fat","carbs")) > 105 or
            numbers["sugar"] is not None and numbers["sugar"] > numbers["carbs"] + 1 or
            numbers["sat_fat"] is not None and numbers["sat_fat"] > numbers["fat"] + 1 or
            numbers["kcal"] == 0 and (numbers["protein"] > .5 or numbers["fat"] > .5)):
            raise ValueError(f"FOODfiles conflicting/outside sane composition: {fid}")
        name = foods[fid]["Food Name"]
        alias_cells = []
        for key in ("Short Food Name", "AlternativeNames"):
            alias = foods[fid][key]
            if alias and alias != name and alias not in alias_cells:
                alias_cells.append(alias)
        # Room stores one alias per language. This derived search alias contains each complete
        # source cell, separated by a newline; the original food name remains the display name.
        # Native en and alias en have equal language preference, so DISPLAY_NAME cannot promote
        # this search text over the native name. No short/alternative source cell is discarded.
        aliases = [("en", "\n".join(alias_cells))] if alias_cells else []
        density_values = densities[fid]
        result.append(dict(id=food_id(fid), name=name, brand=None, barcode=None, lang="en", source="foodfiles",
            country="NZ", carbs_convention="available", aliases=aliases, nutrition_basis="g",
            nutrition_metadata=metadata, density=float(next(iter(density_values))) if len(density_values)==1 else None,
            servings=sorted(portions[fid]), **numbers))
    if report is not None:
        report.update(inputFoods=len(foods), retainedFoods=len(result),
            quarantinedFoods={fid:reason for fid,reason in QUARANTINE.items() if fid in foods},
            omittedPortions=omitted_portions, retainedPortions=sum(len(f["servings"]) for f in result))
    return result
