#!/usr/bin/env python3
"""Unit tests of scripts/android_sdk_packages.py and of the Android jobs of the workflows.

Run: python3 -m unittest discover -s scripts/tests
"""

import os
import sys
import unittest

SCRIPTS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_DIR)

from android_sdk_packages import CATALOG, catalog_versions, sdk_packages  # noqa: E402

WORKFLOWS_DIR = os.path.join(os.path.dirname(SCRIPTS_DIR), ".github", "workflows")


class CatalogTests(unittest.TestCase):
    def test_reads_only_the_versions_table(self):
        text = '[versions]\ncmake = "3.31.6"\nndk = "29.0.1"\n\n[libraries]\ncmake = "x:y:1"\n'
        self.assertEqual(catalog_versions(text), {"cmake": "3.31.6", "ndk": "29.0.1"})

    def test_packages(self):
        text = '[versions]\ncmake = "3.31.6"\nndk = "29.0.1"\n'
        self.assertEqual(sdk_packages(text), ["platform-tools", "cmake;3.31.6", "ndk;29.0.1"])

    def test_a_missing_version_is_an_error(self):
        with self.assertRaises(ValueError):
            sdk_packages('[versions]\ncmake = "3.31.6"\n')

    def test_the_real_catalog_has_all_the_versions(self):
        with open(CATALOG, encoding="utf-8") as catalog:
            packages = sdk_packages(catalog.read())
        self.assertTrue(any(p.startswith("cmake;") for p in packages))
        self.assertTrue(any(p.startswith("ndk;") for p in packages))


class WorkflowTests(unittest.TestCase):
    """Each workflow job that builds Android code installs the packages of the catalog."""

    def test_each_android_build_job_installs_the_catalog_packages(self):
        for name in ("validation.yml", "build-release.yml"):
            with open(os.path.join(WORKFLOWS_DIR, name), encoding="utf-8") as workflow:
                text = workflow.read()
            self.assertIn("python3 scripts/android_sdk_packages.py", text, name)
            self.assertIn("packages: ${{ steps.sdk.outputs.packages }}", text, name)


if __name__ == "__main__":
    unittest.main()
