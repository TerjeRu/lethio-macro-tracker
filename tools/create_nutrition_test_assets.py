"""Generate synthetic Android fixtures with the production catalogue writer, outside assets/main."""
import argparse
import json
from pathlib import Path

from build_nutrition_db import Food, write_db
from nutrition_values import parse_nutrient


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    folder = Path(args.out)
    folder.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    values = json.loads((root / "app/src/test/resources/nutrition-contract.json").read_text(encoding="utf-8"))["nutrients"]
    schema = str(root / "app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json")
    for name, kcal, density, version in [("nutrition-fixture.db", 40, None, 18),
                                         ("nutrition-replacement.db", 400, 0.8, 19)]:
        current = dict(values)
        current["CALORIES"] = parse_nutrient(str(kcal), "kcal", "CALORIES")
        food = Food(999, "Synthetic volume food", None, "4012345678901", kcal, 2.5, 0, 3,
                    sodium=0.4, source="usda", lang="en", density=density,
                    nutrition_basis="ml", nutrition_metadata=current)
        write_db(str(folder / name), [food], schema, version, "synthetic")


if __name__ == "__main__":
    main()
