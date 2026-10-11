import csv
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVALUATOR = ROOT / "tools/evaluate_search_relevance.py"


def run(rows, fixture_text):
    with tempfile.TemporaryDirectory() as directory:
        result_path = Path(directory) / "results.tsv"
        fixture_path = Path(directory) / "fixture.tsv"
        with result_path.open("w", encoding="utf-8", newline="") as fh:
            csv.writer(fh, delimiter="\t", lineterminator="\n").writerows(rows)
        fixture_path.write_text(fixture_text, encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(EVALUATOR), str(result_path), "--fixture", str(fixture_path)],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )


def good_results_pass():
    fixture = (
        "milk whole\tUS\ten\t(?i)milk\t(?i)waffle\n"
        "leche\tES\tes\t(?i)milk|(?i)leche\t(?i)fermented\n"
    )
    rows = [
        ("butter salted", "US", "en", "Butter, salted"),
        ("milk whole", "US", "en", "Milk, whole"),
        ("bread wholemeal", "UK", "en", "Bread, wholemeal"),
        ("whole wheat bread", "US", "en", "Whole wheat bread"),
        ("boiled potato", "US", "en", "Potato, boiled"),
        ("chicken breast raw", "US", "en", "Chicken breast, raw"),
        ("olive oil extra virgin", "FR", "en", "Olive oil, extra virgin"),
        ("orange juice", "US", "en", "Orange juice"),
        ("peanut butter", "US", "en", "Peanut butter"),
        ("rice cooked", "US", "en", "Rice, cooked"),
        ("leche", "ES", "es", "Leche, desnatada, pasteurizada"),
        ("huile", "FR", "fr", "Huile d'olive"),
    ]
    completed = run(rows, fixture)
    assert completed.returncode == 0, completed.stdout + completed.stderr


def bad_results_fail():
    fixture = "milk whole\tUS\ten\t(?i)milk\t(?i)waffle\n"
    completed = run([("milk whole", "US", "en", "Waffle, whole milk")], fixture)
    assert completed.returncode == 1


def missing_results_fail():
    fixture = "milk whole\tUS\ten\t(?i)milk\t(?i)waffle\n"
    completed = run([], fixture)
    assert completed.returncode == 1


if __name__ == "__main__":
    good_results_pass()
    bad_results_fail()
    missing_results_fail()
    print("ok: search relevance evaluator")
