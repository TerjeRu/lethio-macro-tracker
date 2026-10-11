from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
PRODUCTION_RELAY = "https://www.lethio.com/v1/off/contributions"


class GradleOffRelayGateTest(unittest.TestCase):
    def run_gradle(self, properties: str, *tasks: str) -> subprocess.CompletedProcess[str]:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", suffix=".properties", delete=False
        ) as stream:
            stream.write(properties)
            properties_path = Path(stream.name)
        self.addCleanup(properties_path.unlink, missing_ok=True)
        return subprocess.run(
            [
                str(WRAPPER),
                *tasks,
                f"-PoffRelayPropertiesPath={properties_path}",
                "--console=plain",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )

    def test_debug_production_relay_requires_exact_local_opt_in(self) -> None:
        denied = self.run_gradle(f"url={PRODUCTION_RELAY}\n", "help")
        self.assertNotEqual(denied.returncode, 0)
        self.assertIn("allowProductionRelay=true", denied.stdout + denied.stderr)

        allowed = self.run_gradle(
            f"url={PRODUCTION_RELAY}\nallowProductionRelay=true\n",
            "help",
        )
        self.assertEqual(allowed.returncode, 0, allowed.stdout + allowed.stderr)

        nonproduction = self.run_gradle("url=https://relay.example/v1/off/contributions\n", "help")
        self.assertEqual(nonproduction.returncode, 0, nonproduction.stdout + nonproduction.stderr)

    def test_release_lifecycle_tasks_include_secret_verifiers(self) -> None:
        result = self.run_gradle(
            "url=\n",
            ":app:bundleRelease",
            ":app:assembleRelease",
            "--dry-run",
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(":app:verifyReleaseBundleSecrets", result.stdout)
        self.assertIn(":app:verifyReleaseApkSecrets", result.stdout)

    def test_merged_manifest_disables_android_backup(self) -> None:
        result = self.run_gradle("url=\n", ":app:processDebugManifest")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        manifests = list(
            (ROOT / "app/build/intermediates/merged_manifests/debug").rglob("AndroidManifest.xml")
        )
        self.assertEqual(len(manifests), 1, manifests)
        application = ET.parse(manifests[0]).getroot().find("application")
        self.assertIsNotNone(application)
        android = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual(application.attrib.get(f"{android}allowBackup"), "false")


if __name__ == "__main__":
    unittest.main()
