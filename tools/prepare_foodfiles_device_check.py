"""Write independent FOODfiles expectations from the raw files, for checking the catalogue on a device."""

import csv
import hashlib
import json
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

from verify_foodfiles_import import FIELDS, FLAGS, HIDDEN, OMITTED, verify


def prepare(source: str, database: str, output: str) -> dict:
    reconciliation = verify(source, database)
    if reconciliation["errors"]:
        raise ValueError("Catalogue failed independent FOODfiles reconciliation")
    root = Path(source).parent

    def read(name):
        with (root / name).open(encoding="utf-8-sig", newline="") as stream:
            next(stream)
            return list(csv.DictReader(stream, delimiter="~"))

    names = {r["FoodID"]: r for r in read("Names")}
    values = {
        (r["FoodID"], r["Component Identifier"]): r
        for r in read(Path(source).name)
        if r["Component Identifier"] in FIELDS
    }
    portions, densities = defaultdict(list), defaultdict(set)
    for row in read("Csm"):
        fid, label, mass = row["FoodID"], row["CSM"], Decimal(row["Measure"])
        if fid in OMITTED:
            continue
        if row["Density (g/cm3)"]:
            densities[fid].add(Decimal(row["Density (g/cm3)"]))
        if (fid, label) in HIDDEN or (
            fid == "M1148" and label == "1 cup (250 mL) diced " and mass == 200
        ):
            continue
        portions[fid].append(dict(label=label, grams=float(mass)))
    foods = []
    for fid in sorted(names.keys() - OMITTED):
        nutrients = {}
        for code, (kind, _, unit) in FIELDS.items():
            row = values.get((fid, code))
            raw = row["Value"] if row else None
            flag = row["Value Type Code"] if row else ""
            nutrients[kind] = dict(
                value=float(Decimal(raw) / (1000 if unit == "mg" else 1)) if raw else None,
                sourceValue=raw,
                sourceUnit=unit,
                sourceValueType=flag,
                qualifiers=[FLAGS[flag]] if flag else [],
                nutrientCode=code,
                references=(
                    [
                        dict(code=key, reference=row[key])
                        for key in (
                            "Source Code",
                            "Acquisition Type Code",
                            "Method Type Code",
                            "Method Indicator Code",
                        )
                    ]
                    if row
                    else []
                ),
            )
        density = next(iter(densities[fid])) if len(densities[fid]) == 1 else None
        foods.append(
            dict(
                id=210_000_000_000 + (ord(fid[0]) - 65) * 100_000_000 + int(fid[1:]),
                publisherId=fid,
                name=names[fid]["Food Name"],
                density=float(density) if density else None,
                portions=sorted(portions[fid], key=lambda p: (p["label"], p["grams"])),
                nutrients=nutrients,
            )
        )
    result = dict(
        catalogueSha256=hashlib.sha256(Path(database).read_bytes()).hexdigest(),
        reconciliation=reconciliation,
        foods=foods,
    )
    Path(output).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--db", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    result = prepare(args.source, args.db, args.out)
    print(json.dumps(dict(catalogueSha256=result["catalogueSha256"], foods=len(result["foods"]))))
