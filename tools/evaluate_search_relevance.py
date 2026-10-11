#!/usr/bin/env python3
"""Evaluate an Android search-result export against the relevance fixture."""

from __future__ import annotations

import argparse
import csv
import re
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Case:
    query: str
    country: str
    language: str
    acceptable: tuple[re.Pattern[str], ...]
    unacceptable: tuple[re.Pattern[str], ...]
    # True when the fixture's acceptable cell is `-`: this table has no generic row for this
    # food, hand-checked. A DATA gap, not a ranking one. See the staple fixture's header for why
    # the two must not be added together -- counting them as ranking failures means hunting
    # for rows that are not there.
    no_row: bool = False


INLINE_IGNORECASE = re.compile(r"\(\?i\)")


def compile_patterns(value: str) -> tuple[re.Pattern[str], ...]:
    """Compile one fixture cell of `|`-separated patterns.

    The cell is split before compiling, so an inline `(?i)` written mid-cell would bind only to
    the alternative it sits on. That silently made `leche` in `(?i)milk|leche` case-sensitive, so
    it never matched "Leche, desnatada, pasteurizada" and reported two correctly ranked queries as
    failures. Leaving the flag in place is not an option either -- Python rejects a global flag
    that is not at the start of its own pattern. So the flag is read once for the whole cell,
    which is what writing it at all was meant to say.
    """
    if not value:
        return ()
    parts = value.split("|")
    flags = re.IGNORECASE if any(INLINE_IGNORECASE.search(part) for part in parts) else 0
    return tuple(re.compile(INLINE_IGNORECASE.sub("", part), flags) for part in parts)


def load_cases(path: Path) -> list[Case]:
    cases = []
    with path.open(encoding="utf-8", newline="") as fh:
        for number, row in enumerate(csv.reader(fh, delimiter="\t"), 1):
            if not row or row[0].startswith("#"):
                continue
            if not 4 <= len(row) <= 5:
                raise ValueError(f"{path}:{number}: expected 4 or 5 columns, got {len(row)}")
            if len(row) == 4:
                # A case with nothing to reject needs no fifth cell, and requiring an empty one
                # is how a hand-written fixture picks up trailing-tab noise.
                row = row + [""]
            no_row = row[3].strip() == "-"
            cases.append(
                Case(
                    row[0],
                    row[1],
                    row[2],
                    () if no_row else compile_patterns(row[3]),
                    compile_patterns(row[4]),
                    no_row,
                )
            )
    if not cases:
        raise ValueError(f"{path}: no cases")
    return cases


def evaluate(cases: list[Case], results_path: Path, top_k: int) -> int:
    results: dict[tuple[str, str, str], list[str]] = {}
    with results_path.open(encoding="utf-8", newline="") as fh:
        for number, row in enumerate(csv.reader(fh, delimiter="\t"), 1):
            if not row or row[0].startswith("#"):
                continue
            if len(row) != 4:
                raise ValueError(f"{results_path}:{number}: expected 4 columns, got {len(row)}")
            query, country, language, name = row
            results.setdefault((query, country, language), []).append(name)

    passed = 0
    no_row = 0
    for case in cases:
        names = results.get((case.query, case.country, case.language), [])[:top_k]
        if case.no_row:
            no_row += 1
            print(
                f"NOROW {case.query} [{case.country}/{case.language}] "
                f"got={names[0] if names else '(nothing)'}"
            )
            continue
        acceptable = any(pattern.search(name) for name in names for pattern in case.acceptable)
        bad = [name for name in names if any(pattern.search(name) for pattern in case.unacceptable)]
        ok = acceptable and not bad
        passed += ok
        print(
            f"{'PASS' if ok else 'FAIL'} {case.query} [{case.country}/{case.language}]"
            + (f" got={names[0] if names else '(nothing)'}" if not ok else "")
        )
    rankable = len(cases) - no_row
    print(f"summary: {passed}/{rankable} passed; top_k={top_k}")
    if no_row:
        # Reported, never added in. These are the queries where no ORDER BY could win.
        print(f"coverage: {no_row} of {len(cases)} have no generic row in the reader's table")
    return 0 if passed == rankable else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument(
        "--fixture", type=Path, default=Path("app/src/test/resources/search-relevance-fixtures.tsv")
    )
    parser.add_argument("--top-k", type=int, default=5)
    args = parser.parse_args()
    return evaluate(load_cases(args.fixture), args.results, args.top_k)


if __name__ == "__main__":
    raise SystemExit(main())
