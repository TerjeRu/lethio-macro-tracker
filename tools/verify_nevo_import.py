"""Independent reconciliation of a written NEVO catalogue against its publisher CSVs.

No loader/parser imports: expected storage comes directly from decimal source figures.
"""

import argparse
import csv
import hashlib
import json
import math
import sqlite3
from collections import Counter, defaultdict
from contextlib import closing
from decimal import Decimal
from pathlib import Path


def verify(source, database):
    source = Path(source)

    def rows(path):
        with path.open(encoding="utf-8-sig", newline="") as f:
            return list(csv.DictReader(f, delimiter="|"))

    raw = rows(source)
    details_path = source.with_name(source.stem + "_Details.csv")
    details = rows(details_path)
    fields = [
        ("ENERCC", "kcal", "CALORIES", "kcal_100g"),
        ("PROT", "g", "PROTEIN", "protein_100g"),
        ("FAT", "g", "FAT", "fat_100g"),
        ("CHO", "g", "CARBS", "carbs_100g"),
        ("FIBT", "g", "FIBRE", "fiber_100g"),
        ("SUGAR", "g", "SUGAR", "sugar_100g"),
        ("FASAT", "g", "SATURATED_FAT", "sat_fat_100g"),
        ("NA", "mg", "SODIUM", "sodium_100g"),
    ]
    refs = defaultdict(set)
    detail_counts = Counter()
    for r in details:
        if r["Nutrient-code"] in {f[0] for f in fields}:
            key = r["NEVO-code"], r["Nutrient-code"]
            refs[key].add((r["Broncode/Source code"], r["Referentie/Reference"]))
            detail_counts[key] += 1
    errors = []

    def check(condition, reason):
        if not condition:
            errors.append(reason)

    def number(text, unit="g"):
        return (
            float(Decimal(text.replace(",", ".")) / (1000 if unit == "mg" else 1)) if text else None
        )

    missing, traces, fortified, bases = Counter(), Counter(), Counter(), Counter()
    energy = []
    with closing(sqlite3.connect(database)) as db:
        db.row_factory = sqlite3.Row
        written = {r["id"]: r for r in db.execute("SELECT * FROM foods WHERE source='nevo'")}
        expected_ids = {200_000_000_000 + int(r["NEVO-code"]) for r in raw}
        check(set(written) == expected_ids, "input/written ID reconciliation")
        for r in raw:
            source_id = r["NEVO-code"]
            food_id = 200_000_000_000 + int(source_id)
            if food_id not in written:
                continue
            food = written[food_id]
            check(
                food["name"] == r["Voedingsmiddelnaam/Dutch food name"], f"{source_id}: native name"
            )
            check(
                food["country"] == "NL"
                and food["lang"] == "nl"
                and food["carbs_convention"] == "available",
                f"{source_id}: country/language/carbs",
            )
            basis = {"per 100g": "g", "per 100ml": "ml"}[r["Hoeveelheid/Quantity"]]
            bases[basis] += 1
            check(food["nutrition_basis"] == basis, f"{source_id}: denominator")
            check(food["density_g_per_ml"] is None, f"{source_id}: invented density")
            check(
                db.execute(
                    "SELECT COUNT(*) FROM food_servings WHERE food_id=?", (food_id,)
                ).fetchone()[0]
                == 0,
                f"{source_id}: invented portion",
            )
            aliases = {
                tuple(a)
                for a in db.execute(
                    "SELECT lang,name FROM food_aliases WHERE food_id=?", (food_id,)
                )
            }
            check(
                r["Engelse naam/Food name"] == food["name"]
                or ("en", r["Engelse naam/Food name"]) in aliases,
                f"{source_id}: English name",
            )
            if r["Synoniem"] and r["Synoniem"] != r["Voedingsmiddelnaam/Dutch food name"]:
                check(("nl", r["Synoniem"]) in aliases, f"{source_id}: published synonym")
            metadata = json.loads(food["nutrition_metadata"])["nutrients"]
            check(set(metadata) == {f[2] for f in fields}, f"{source_id}: selected coverage")
            trace_codes = set(r["Bevat sporen van/Contains traces of"].split(", "))
            fortified_codes = set(r["Is verrijkt met/Is fortified with"].split(", "))
            for code, unit, kind, column in fields:
                text = r[f"{code} ({unit})"]
                expected = number(text, unit)
                value = metadata.get(kind, {})
                for actual in (food[column], value.get("value")):
                    check(
                        (
                            actual is None
                            if expected is None
                            else actual is not None
                            and math.isclose(actual, expected, rel_tol=1e-14, abs_tol=1e-14)
                        ),
                        f"{source_id}/{code}: numeric figure",
                    )
                check(
                    value.get("sourceValue") == text and value.get("sourceUnit") == unit,
                    f"{source_id}/{code}: original notation/unit",
                )
                flag = "TR" if code in trace_codes else "+" if code in fortified_codes else None
                check(
                    value.get("sourceValueType") == flag, f"{source_id}/{code}: source annotation"
                )
                check(
                    value.get("qualifiers", []) == (["TRACE"] if code in trace_codes else []),
                    f"{source_id}/{code}: qualifier",
                )
                origin = value.get("sourceOrigin", {})
                check(
                    {k: origin.get(k) for k in ("dataset", "version", "foodId", "nutrientCode")}
                    == dict(
                        dataset="nevo",
                        version=r["NEVO-versie/NEVO-version"],
                        foodId=source_id,
                        nutrientCode=code,
                    ),
                    f"{source_id}/{code}: origin",
                )
                check(
                    {(a["code"], a["reference"]) for a in origin.get("references", [])}
                    == refs[source_id, code],
                    f"{source_id}/{code}: all provenance references",
                )
                missing[code] += expected is None
                traces[code] += code in trace_codes
                fortified[code] += code in fortified_codes
            macro_energy = (
                4 * number(r["PROT (g)"]) + 9 * number(r["FAT (g)"]) + 4 * number(r["CHO (g)"])
            )
            kcal = number(r["ENERCC (kcal)"])
            if abs(kcal - macro_energy) > max(35, 0.30 * kcal):
                # RIVM MI0115: carbohydrate includes polyols but excludes fibre.
                predicted = (
                    macro_energy
                    + 2 * (number(r["FIBT (g)"]) or 0)
                    + 7 * (number(r["ALC (g)"]) or 0)
                    - 1.6 * (number(r["POLYL (g)"]) or 0)
                    + 3 * (number(r["OA (g)"]) or 0)
                )
                energy.append(
                    dict(
                        food_id=source_id,
                        published_kcal=kcal,
                        four_four_nine=macro_energy,
                        full_source_formula=predicted,
                        difference=kcal - predicted,
                    )
                )
        check(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity")
        check(db.execute("PRAGMA user_version").fetchone()[0] == 8, "Room schema version")
    return dict(
        source=str(source),
        database=str(database),
        source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        input_count=len(raw),
        retained_count=len(written),
        omissions=[],
        selected_figures=len(raw) * 8,
        bases=dict(bases),
        missing=dict(missing),
        traces=dict(traces),
        fortified=dict(fortified),
        repeated_selected_detail_keys=sum(n > 1 for n in detail_counts.values()),
        references=sum(len(v) for v in refs.values()),
        energy_explanations=energy,
        errors=errors,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--db", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    result = verify(args.source, args.db)
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k not in ("energy_explanations",)}))
    raise SystemExit(bool(result["errors"]))
