#!/usr/bin/env python3
"""Pins fold_name() to the fixtures shared with the app.

`fold_name()` folds the name stored in `foods.name_folded`; `foldForSearch` in
app/src/main/java/com/lethio/macros/data/food/NameFolding.kt folds the user's query. They sit on
opposite sides of the same comparison, so if they ever disagree, search ranking degrades for
exactly the accented languages the folding was added for and **nothing fails** -- results merely
get quietly worse.

app/src/test/resources/name-folding-fixtures.tsv is what makes that drift visible: NameFoldingTest
asserts the same rows from the same file. Regenerating the fixtures to make this pass is only
correct if the Kotlin side was changed to match in the same commit.

    python tools/test_name_folding.py
"""

from __future__ import annotations

import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_nutrition_db import fold_name  # noqa: E402  (needs the path above)

FIXTURES = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app",
    "src",
    "test",
    "resources",
    "name-folding-fixtures.tsv",
)


def main() -> int:
    if not os.path.exists(FIXTURES):
        print(f"FAIL  fixture file missing: {FIXTURES}")
        return 1

    checked = 0
    failures: list[str] = []
    with io.open(FIXTURES, encoding="utf-8") as fh:
        for number, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 2:
                failures.append(f"line {number}: malformed, expected 2 tab-separated fields")
                continue
            source, expected = parts
            actual = fold_name(source)
            checked += 1
            if actual != expected:
                failures.append(f"line {number}: {source!r} -> {actual!r}, expected {expected!r}")
            # Folded once per build and once per query, so a second pass must be a no-op --
            # otherwise the two sides would depend on how many times each had been applied.
            if fold_name(actual) != actual:
                failures.append(f"line {number}: {source!r} is not idempotent")

    if not checked:
        print("FAIL  fixture file contained no cases")
        return 1

    for failure in failures:
        print(f"FAIL  {failure}")
    print(
        f"{'FAIL' if failures else 'ok  '}  fold_name: {checked} fixtures, {len(failures)} failures"
    )
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
