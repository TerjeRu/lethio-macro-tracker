#!/usr/bin/env python3
"""Regenerate `app/src/main/assets/compound-lexicon.txt` from an already-built database.

The lexicon is derived from the generic food names, so `build_nutrition_db.py`
writes it as part of a build. But the app's query-side splitter needs it against the database
that is ALREADY bundled, and rebuilding a 110 MB asset to produce a 60 KB text file would be a
absurd -- and is forbidden by the task's own boundary, which says no `seed.db` rebuild.

So this reads the names back out of a built database and calls the same
`lexicon_from_names()` the build calls. There is one derivation, imported, not copied: the one
thing that must never drift here is which words the lexicon thinks exist, because the query
side splits against it and the index was built from it.

The output is byte-identical to what a build of the same database would write. That is
asserted by --verify, which rebuilds the lexicon and compares it with the file on disk.

Usage:
    python tools/export_compound_lexicon.py --db app/src/main/assets/seed.db
    python tools/export_compound_lexicon.py --db app/src/main/assets/seed.db --verify
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_nutrition_db import (  # noqa: E402
    lexicon_from_names, lexicon_lines, write_compound_lexicon,
)

DEFAULT_OUT = ROOT / "app/src/main/assets/compound-lexicon.txt"


def lexicons_from_db(db_path: Path) -> dict[tuple[str, str], set[str]]:
    """{(source, lang): vocabulary} over the generic rows, mirroring compound_lexicons().

    `source <> 'off'` is the same generic/branded split the search ladder uses. Branded names
    are brands, not vocabulary: including them would let a product name teach the splitter a
    word no national table can answer with.

    Keyed per source; see compound_lexicons() for why.
    """
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
