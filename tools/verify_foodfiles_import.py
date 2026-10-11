"""Independent raw-to-written FOODfiles reconciliation. Imports no loader/nutrient parser."""

import csv
import json
import math
import sqlite3
from collections import defaultdict
from contextlib import closing
from decimal import Decimal
from pathlib import Path

FIELDS = {
    "ENERC_FSANZ2_KCAL": ("CALORIES", "kcal_100g", "kcal"),
    "PROT": ("PROTEIN", "protein_100g", "g"),
    "FAT": ("FAT", "fat_100g", "g"),
    "CHOAVL_FSANZ": ("CARBS", "carbs_100g", "g"),
    "FIBTG": ("FIBRE", "fiber_100g", "g"),
    "SUGAR": ("SUGAR", "sugar_100g", "g"),
    "FASAT": ("SATURATED_FAT", "sat_fat_100g", "g"),
    "NA": ("SODIUM", "sodium_100g", "mg"),
}
FLAGS = {
    "BE": "BEST_ESTIMATE",
    "W": "WEIGHTED",
    "LZ": "LOGICAL_ZERO",
    "X": "UNSPECIFIED",
    "BL": "BELOW_DETECTION",
    "BQ": "BELOW_QUANTIFICATION",
    "UD": "UNDECIDABLE",
    "AV": "AVERAGE",
}
OMITTED = {"B1035", "C59", "P10010"}
# Independent review expectations, not imported from the production reader.
HIDDEN = {
    ("P10005", "1 pinch fine"): "0.32",
    ("P10007", "1 pinch fine"): "0.38",
    ("P24", "1 teaspoon (5 mL)"): "0.6",
    ("P27", "1 teaspoon (5 mL)"): "0.6",
    ("P31", "1 teaspoon (5 mL)"): "0.6",
    ("P40", "1 teaspoon (5 mL)"): "0.6",
    ("P46", "1 teaspoon (5 mL)"): "0.3",
    ("P54", "1 teaspoon (5 mL)"): "0.7",
    ("X1014", "1 roll sushi (approximately, 42 g) seaweed wrap"): "0.95",
}


def verify(source: str, target: str) -> dict:
    root = Path(source).parent

    def read(name):
        with (root / name).open(encoding="utf-8-sig", newline="") as stream:
            next(stream)
            return list(csv.DictReader(stream, delimiter="~"))

    names = {r["FoodID"]: r for r in read("Names")}
    raw = {
        (r["FoodID"], r["Component Identifier"]): r
        for r in read(Path(source).name)
        if r["Component Identifier"] in FIELDS
    }
    portions = defaultdict(list)
    densities = defaultdict(set)
    for r in read("Csm"):
        fid = r["FoodID"]
        if fid in OMITTED:
            continue
        if r["Density (g/cm3)"]:
            densities[fid].add(Decimal(r["Density (g/cm3)"]))
        if fid == "M1148" and r["CSM"] == "1 cup (250 mL) diced " and Decimal(r["Measure"]) == 200:
            continue
        reviewed = HIDDEN.get((fid, r["CSM"]))
        if reviewed is not None:
            if Decimal(r["Measure"]) != Decimal(reviewed):
                raise ValueError("Reviewed hidden source portion changed: " + fid)
            continue
        portions[fid].append((r["CSM"], float(Decimal(r["Measure"]))))
    errors = []
    slots = 0

    def check(condition, message):
        if not condition:
            errors.append(message)

    def number_equal(actual, expected):
        return (
            actual is None
            if expected is None
            else actual is not None
            and math.isclose(actual, float(expected), rel_tol=1e-12, abs_tol=1e-12)
        )

    with closing(sqlite3.connect(target)) as db:
        db.row_factory = sqlite3.Row
        rows = {r["id"]: r for r in db.execute("SELECT * FROM foods WHERE source='foodfiles'")}
        expected_ids = {
            210_000_000_000 + (ord(fid[0]) - 65) * 100_000_000 + int(fid[1:]): fid
            for fid in names
            if fid not in OMITTED
        }
        check(rows.keys() == expected_ids.keys(), "source IDs and exact reviewed omissions")
        for app_id, fid in expected_ids.items():
            row = rows.get(app_id)
            if row is None:
                continue
            check(row["name"] == names[fid]["Food Name"], fid + ": original name")
            check(
                row["lang"] == "en" and row["country"] == "NZ" and row["nutrition_basis"] == "g",
                fid + ": language/country/basis",
            )
            check(row["carbs_convention"] == "available", fid + ": carbohydrate definition")
            metadata = json.loads(row["nutrition_metadata"])["nutrients"]
            for code, (kind, column, unit) in FIELDS.items():
                r = raw.get((fid, code))
                slots += 1
                expected = (
                    Decimal(r["Value"]) / (1000 if unit == "mg" else 1)
                    if r and r["Value"]
                    else None
                )
                value = metadata.get(kind, {})
                check(number_equal(row[column], expected), fid + "/" + code + ": written figure")
                check(
                    number_equal(value.get("value"), expected),
                    fid + "/" + code + ": metadata figure",
                )
                check(
                    value.get("sourceValue") == (r["Value"] if r else None)
                    and value.get("sourceUnit") == unit,
                    fid + "/" + code + ": original notation/unit",
                )
                flag = r["Value Type Code"] if r else ""
                check(
                    value.get("sourceValueType", "") == flag
                    and value.get("qualifiers", []) == ([FLAGS[flag]] if flag else []),
                    fid + "/" + code + ": original type/qualifier",
                )
                origin = value.get("sourceOrigin", {})
                check(
                    origin.get("dataset") == "foodfiles"
                    and origin.get("version") == "FOODfiles 2024 v1"
                    and origin.get("foodId") == fid
                    and origin.get("nutrientCode") == code,
                    fid + "/" + code + ": source identity",
                )
                expected_refs = (
                    [
                        {"code": key, "reference": r[key]}
                        for key in (
                            "Source Code",
                            "Acquisition Type Code",
                            "Method Type Code",
                            "Method Indicator Code",
                        )
                    ]
                    if r
                    else []
                )
                check(
                    origin.get("references") == expected_refs,
                    fid + "/" + code + ": all provenance codes",
                )
            actual = [
                (r["label"], r["grams"])
                for r in db.execute(
                    "SELECT label,grams FROM food_servings WHERE food_id=? ORDER BY label,grams",
                    (app_id,),
                )
            ]
            check(
                actual == sorted(portions[fid]),
                fid + ": measured portions and reviewed cup omission",
            )
            density = next(iter(densities[fid])) if len(densities[fid]) == 1 else None
            check(
                number_equal(row["density_g_per_ml"], density),
                fid + ": unique source density or unknown",
            )
            source_cells = []
            for key in ("Short Food Name", "AlternativeNames"):
                name = names[fid][key]
                if name and name != names[fid]["Food Name"] and name not in source_cells:
                    source_cells.append(name)
            alias = db.execute(
                "SELECT name FROM food_aliases WHERE food_id=? AND lang='en'", (app_id,)
            ).fetchone()
            check(
                (alias[0] if alias else None)
                == ("\n".join(source_cells) if source_cells else None),
                fid + ": complete search name cells",
            )
        check(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity")
    return dict(
        inputFoods=len(names),
        retainedFoods=len(rows),
        quarantinedFoods=sorted(OMITTED & names.keys()),
        selectedSlots=slots,
        retainedPortions=sum(map(len, portions.values())),
        errors=errors,
    )


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--db", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    report = verify(args.source, args.db)
    Path(args.out).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    raise SystemExit(bool(report["errors"]))
