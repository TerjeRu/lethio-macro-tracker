# Lethio Macro Tracker

A calorie diary for Android built on the national food composition tables of fifteen
countries. Search your country's table first, see where each result comes from, and keep
your diary on your phone.

[Open Food Facts](https://world.openfoodfacts.org/) was the main inspiration for
Macro Tracker. Its contributors make food-label information available to everyone.
The app includes over half a million products from their database, and you can
contribute missing products from the scanner.

[About the app](https://macros.lethio.com/) ·
[Google Play](https://play.google.com/store/apps/details?id=com.lethio.macros) ·
[Privacy policy](https://macros.lethio.com/#privacy) ·
[Open Food Facts data](https://github.com/TerjeRu/lethio-food-db)

The diary shows what is left of the day's calories and macros at the top, with a + on
every meal to add straight to it. You can copy a meal from the day before, swipe an entry
away to delete it, and undo either. Entries can be corrected after logging. History shows a
week or four against your targets, with a one-line summary and exact daily values. Saved
goals can be edited or deleted for their date range.

The app also supports daily calorie and macro targets, weight tracking, barcode
scanning, custom foods, portion sizes, and CSV export. The interface is available in English,
Norwegian Bokmål, Swedish, Danish, Finnish, Dutch, German, French, Spanish, European Portuguese,
and Italian.
It runs on Android 8.0 (API 26) or later.

This repository contains the Android source and nutrition database build tools.
Start by downloading the source data and building a USDA-based catalogue with the
instructions below. Add further datasets to expand its coverage.

## Build

You need JDK 17, Android SDK Platform 36 and its build tools, and Python 3.14 with
SQLite FTS5 support. The Gradle wrapper downloads the pinned Gradle version;
dependencies are resolved from Google Maven and Maven Central. The database tools
use Python's standard library.

Set `JAVA_HOME` to your JDK 17 installation and `ANDROID_HOME` to your Android SDK.
Alternatively, open the project in Android Studio, select JDK 17 for Gradle, and
let Studio create your local SDK configuration.

### 1. Build the food database

Download the **SR Legacy CSV ZIP (April 2018)** from
[USDA FoodData Central](https://fdc.nal.usda.gov/download-datasets/). Put the ZIP in
a local `raw/` directory, keeping it zipped. USDA data is public domain.

Run this command from the repository root (`python3` may be needed instead of `python`):

```sh
python tools/build_nutrition_db.py --usda raw/FoodData_Central_sr_legacy_food_csv_2018-04.zip --schema app/schemas/com.lethio.macros.data.food.FoodDatabase/8.json --out app/src/main/assets/seed.db --build-version 1 --self-check
```

This generates both `seed.db` and `compound-lexicon.txt`. The checks verify the
database schema, integrity, search index, and nutrition constraints. These generated
files and the raw inputs are excluded from Git.

With this catalogue, choose **United States** as your food database in the app's
Settings. USDA SR Legacy covers generic foods. Add Open Food Facts data for local
branded barcode search, or enable the optional online lookup. Each additional
country's catalogue requires its corresponding dataset.

### 2. Build the app

On macOS or Linux:

```sh
./gradlew lintDebug assembleDebug
```

On Windows:

```powershell
.\gradlew.bat lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use
`com.lethio.macros.debug` and Android's development signing key. They can be installed
alongside the production app.

`assembleRelease` produces an unsigned release APK with code and resource shrinking.
To distribute a fork, sign it with your own key and choose your own application ID,
branding, and Open Food Facts contact identity.

## Additional food sources

The [food-data repository](https://github.com/TerjeRu/lethio-food-db) publishes the
Play release's Open Food Facts records as SQLite and CSV downloads under ODbL 1.0.
The database builder uses the upstream formats listed below.

`python tools/build_nutrition_db.py --help` lists all source options. Download your
chosen datasets and pass their local paths to the builder:

| Option | Input |
| --- | --- |
| `--usda` | FoodData Central Foundation or SR Legacy CSV ZIP; repeat for multiple ZIPs |
| `--off` | Open Food Facts gzipped TSV export |
| `--ciqual` | Directory containing Ciqual XML files |
| `--bls` | CSV produced by `tools/extract_bls_csv.py` |
| `--cofid` | CSV produced by `tools/extract_cofid_csv.py` |
| `--matvaretabellen` | Directory containing `foods_en.json` and `foods_nb.json` |
| `--livsmedel` | Directory containing `foods_sprak1.json`, `foods_sprak2.json`, and `naringsvarden.jsonl` |
| `--fineli` | Extracted Fineli open-data CSV release |
| `--frida` | CSV produced by `tools/extract_frida_csv.py` |
| `--swiss` | CSV produced by `tools/extract_swiss_csv.py` |
| `--tca` | CSV produced by `tools/extract_tca_csv.py` |
| `--cnf` | Canadian Nutrient File ZIP |
| `--afcd` | CSV produced by `tools/extract_afcd_csv.py` |
| `--bedca` | `bedca_foods.csv` with `bedca_foodvalues.csv` beside it; restricted terms apply |
| `--nevo` | NEVO 2025/9.0 wide CSV, with its Details and Nutrienten_Nutrients files beside it |
| `--foodfiles` | FOODfiles 2024 v1 StandardDataFT, with its Names, StandardCodes and Csm files beside it |

The extractors accept publisher workbooks; run the relevant script with `--help`
for its arguments. Source links, credits, and license conditions are in
[nutrition data licenses](LICENSES/Nutrition-data.txt). Follow each publisher's terms when distributing
the resulting data.

To add sources, append their options to the database command. Keep the USDA source
when using `--self-check`: its current search smoke check expects an English `milk`
result. `--full-limit` limits branded rows; national-table foods are retained after
validation. `--min-scans` filters Open Food Facts products by their recorded scan
count. `--seed` can write a second catalogue with its own `--seed-limit`.

Increment `--build-version` when replacing a database in an installed app. The app
uses that number to decide whether to install the newly packaged catalogue. The
catalogue has a separate database from the user's diary.

The tools filter invalid nutrition values, normalize names and units, build aliases
and barcode mappings, and generate the search index and compound-word vocabulary.
These are transformed datasets, so distribution must account for each source's
conditions. A database built from newer upstream inputs may differ from an earlier
build.

## Network features

Barcodes are decoded on the device by [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp), which
has no network code: scanning sends nothing. The
[privacy policy](https://macros.lethio.com/#privacy) covers everything else.

The diary and local food search work offline. Open Food Facts lookup and product
contribution have independent settings and are off by default. A lookup sends one
barcode to Open Food Facts only after the user requests it. A contribution requires
approval of the individual product; the app saves the food locally before sending.

To enable contributions in your build, set the `offRelayUrl` Gradle property to the
public HTTPS URL of a compatible relay you operate. Store the Open Food Facts
account credentials on the relay server.

The app sends a salted installation hash with each approved contribution.
The underlying random identifier stays on the device. The public Open
Food Facts user agent is defined in `OffUserAgent.kt`; change the contact identity
when distributing a fork.

## Code layout

- `app/src/main/java/com/lethio/macros/ui`: Compose screens and view models.
- `app/src/main/java/com/lethio/macros/domain`: models, nutrition rules, and repository interfaces.
- `app/src/main/java/com/lethio/macros/data`: Room databases, local search, and Open Food Facts clients.
- `app/schemas`: exported Room schemas used by the database builder and migration history.
- `tools`: database import, transformation, validation, and export code.

The Android app uses Kotlin, Jetpack Compose, Room with bundled SQLite/FTS5, Hilt,
CameraX, and zxing-cpp for barcode decoding. Dependency versions are pinned in
`gradle/libs.versions.toml`; see [third-party notices](NOTICE).

## License

The original application and tooling code is licensed under the [MIT License](LICENSE).
Third-party software and nutrition datasets retain their own licenses.

See [nutrition data licenses](LICENSES/Nutrition-data.txt) for nutrition data and
[third-party notices](NOTICE) for software notices.

After changing runtime dependencies, regenerate the bundled software notices:

```sh
./gradlew :app:exportLicenseInventory
python tools/build_software_notices.py --inventory app/build/license-inventory.json --mpl LICENSES/MPL-2.0.txt --out app/src/main/assets/software-licenses.json
```

On Windows, use `gradlew.bat`. The build checks that the notice inventory matches
the resolved runtime dependencies.
