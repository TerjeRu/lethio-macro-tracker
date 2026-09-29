"""Export Open Food Facts records and related data from a food database."""

import argparse
import csv
import gzip
import pathlib
import sqlite3

OFF_SOURCE = "off"

CARRIED = ["foods", "food_barcodes", "food_servings", "food_aliases"]

SKIPPED_PREFIXES = ("food_search", "room_master_table", "sqlite_", "database_meta")

def ddl_for(src: sqlite3.Connection, table: str) -> str:
    row = src.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (table,)
    ).fetchone()
    if row is None or row[0] is None:
        raise SystemExit(f"no DDL for {table}; the seed schema has changed")
    return row[0]

def export_sqlite(seed: pathlib.Path, out: pathlib.Path) -> int:
    if out.exists():
        out.unlink()
    src = sqlite3.connect(f"file:{seed}?mode=ro", uri=True)
    dst = sqlite3.connect(out)

    for table in CARRIED:
        dst.execute(ddl_for(src, table))

    src.execute("CREATE TEMP TABLE kept AS SELECT id FROM foods WHERE source = ?", (OFF_SOURCE,))
    kept = src.execute("SELECT count(*) FROM kept").fetchone()[0]

    queries = {
        "foods": "SELECT f.* FROM foods f JOIN kept k ON k.id = f.id",
        "food_barcodes": "SELECT b.* FROM food_barcodes b JOIN kept k ON k.id = b.food_id",
        "food_servings": "SELECT s.* FROM food_servings s JOIN kept k ON k.id = s.food_id",
        "food_aliases": "SELECT a.* FROM food_aliases a JOIN kept k ON k.id = a.food_id",
    }

    for table in CARRIED:
        rows = src.execute(queries[table]).fetchall()
        if rows:
            placeholders = ",".join("?" * len(rows[0]))
            dst.executemany(f"INSERT INTO {table} VALUES ({placeholders})", rows)
        print(f"  {table:16} {len(rows):>9,}")

    dst.commit()
    dst.execute("VACUUM")
    dst.close()

    check = sqlite3.connect(f"file:{out}?mode=ro", uri=True)
    sources = [r[0] for r in check.execute("SELECT DISTINCT source FROM foods")]
    if sources != [OFF_SOURCE]:
        raise SystemExit(f"export leaked non-OFF sources: {sources}")
    check.close()

    src.close()
    return kept

def export_csv(seed: pathlib.Path, out: pathlib.Path) -> int:

    if out.exists():
        out.unlink()
    src = sqlite3.connect(f"file:{seed}?mode=ro", uri=True)
    columns = [r[1] for r in src.execute("PRAGMA table_info(foods)")]

    barcodes: dict[int, list[str]] = {}
    for food_id, barcode in src.execute(
        "SELECT b.food_id, b.barcode FROM food_barcodes b "
        "JOIN foods f ON f.id = b.food_id WHERE f.source = ?", (OFF_SOURCE,)
    ):
        barcodes.setdefault(food_id, []).append(barcode)

    written = 0
    with gzip.open(out, "wt", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(columns + ["barcodes"])
        for row in src.execute(
            f"SELECT {','.join(columns)} FROM foods WHERE source = ?", (OFF_SOURCE,)
        ):
            writer.writerow(list(row) + [";".join(barcodes.get(row[0], []))])
            written += 1
    src.close()
    return written

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=pathlib.Path, default=pathlib.Path("app/src/main/assets/seed.db"))
    ap.add_argument("--out", type=pathlib.Path, default=pathlib.Path("build/odbl"))
    ap.add_argument("--build", required=True, help="Database build identifier used in output filenames")
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    db_path = args.out / f"lethio-off-derived-{args.build}.sqlite"
    csv_path = args.out / f"lethio-off-derived-{args.build}.csv.gz"

    print(f"seed: {args.seed}")
    kept = export_sqlite(args.seed, db_path)
    rows = export_csv(args.seed, csv_path)

    if kept != rows:
        raise SystemExit(f"sqlite kept {kept} rows but csv wrote {rows}")

    print(f"\n{kept:,} Open Food Facts rows exported")
    print(f"  {db_path}  {db_path.stat().st_size / 1e6:.1f} MB")
    print(f"  {csv_path}  {csv_path.stat().st_size / 1e6:.1f} MB")

if __name__ == "__main__":
    main()
