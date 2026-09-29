#!/usr/bin/env python3
'Generate the compound vocabulary from a food database.'

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_nutrition_db import (
    lexicon_from_names, lexicon_lines, write_compound_lexicon,
)

DEFAULT_OUT = ROOT / "app/src/main/assets/compound-lexicon.txt"

def lexicons_from_db(db_path: Path) -> dict[tuple[str, str], set[str]]:

    db = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
    try:
        by_source: dict[tuple[str, str], list[str]] = {}
        for source, lang, name in db.execute(
            "SELECT source, lang, name FROM foods "
            "WHERE source <> 'off' AND lang IS NOT NULL "
            "AND id IN (SELECT rowid FROM food_search)"
        ):
            by_source.setdefault((source, lang), []).append(name)
    finally:
        db.close()
    return {key: lexicon_from_names(names) for key, names in by_source.items()}

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--verify", action="store_true",
                        help="Compare with the file on disk instead of writing it")
    args = parser.parse_args()

    if not args.db.is_file():
        raise FileNotFoundError(f"database not found: {args.db}")

    lexicons = lexicons_from_db(args.db)
    expected = "".join(lexicon_lines(lexicons))

    if args.verify:
        if not args.out.is_file():
            print(f"MISSING {args.out}", file=sys.stderr)
            return 1
        actual = args.out.read_text(encoding="utf-8", newline="")
        if actual != expected:
            print(f"STALE {args.out}: does not match a lexicon derived from {args.db}",
                  file=sys.stderr)
            return 1
        print(f"ok: {args.out} matches {args.db}")
        return 0

    sources, words = write_compound_lexicon(lexicons, str(args.out))
    size = args.out.stat().st_size
    print(f"{words:,} words across {sources} sources, {size:,} bytes -> {args.out}")
    for source, lang in sorted(lexicons):
        print(f"  {source}\t{lang}\t{len(lexicons[(source, lang)]):,}")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, sqlite3.Error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)
