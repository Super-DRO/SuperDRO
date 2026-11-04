#!/usr/bin/env python3
"""Unit tests of scripts/changed_test_tasks.py.

Run: python3 -m unittest discover -s scripts/tests
"""

import os
import sys
import unittest

SCRIPTS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_DIR)

from changed_test_tasks import ALL_TESTS, changed_tasks, included_modules, module_of, test_task  # noqa: E402

MODULES = ["core/ui", "core/protocol", "app/android", "app/android/baseline", "validation"]

BUILDS = {
    "core/ui": "plugins { alias(libs.plugins.kotlinMultiplatform) }",
    "core/protocol": "plugins { alias(libs.plugins.kotlinMultiplatform) }",
    "app/android": "plugins { alias(libs.plugins.androidApplication) }",
    "app/android/baseline": "plugins { alias(libs.plugins.androidTest) }",
    "validation": "plugins { alias(libs.plugins.kotlinJvm) }",
}


class ChangedTestTasksTests(unittest.TestCase):
    def test_reads_the_includes(self):
        self.assertEqual(included_modules('include(\n    ":core:ui",\n    ":validation",\n)'), ["core/ui", "validation"])

    def test_the_longest_module_wins(self):
        self.assertEqual(module_of("app/android/baseline/src/Main.kt", MODULES), "app/android/baseline")
        self.assertEqual(module_of("app/android/src/Main.kt", MODULES), "app/android")
        self.assertIsNone(module_of("docs/plan.md", MODULES))
        self.assertIsNone(module_of("core/uix/a.kt", MODULES))

    def test_the_task_follows_the_plugin(self):
        self.assertEqual(test_task("core/ui", BUILDS["core/ui"]), ":core:ui:jvmTest")
        self.assertEqual(test_task("validation", BUILDS["validation"]), ":validation:test")
        self.assertIsNone(test_task("app/android", BUILDS["app/android"]))

    def test_changed_files_give_their_module_tasks(self):
        paths = [
            "core/ui/src/commonMain/kotlin/A.kt",
            "core/ui/src/jvmTest/kotlin/ATest.kt",
            "validation/src/test/kotlin/V.kt",
            "app/android/src/main/M.kt",
            "docs/plan.md",
            ".github/workflows/validation.yml",
        ]
        self.assertEqual(changed_tasks(paths, MODULES, BUILDS.get), [":core:ui:jvmTest", ":validation:test"])

    def test_a_build_change_runs_all_tests(self):
        for path in ("gradle/libs.versions.toml", "composite/src/Main.kt", "build.gradle.kts", "settings.gradle.kts"):
            self.assertEqual(changed_tasks([path], MODULES, BUILDS.get), [ALL_TESTS], path)

    def test_a_module_build_file_is_a_module_change(self):
        self.assertEqual(changed_tasks(["core/protocol/build.gradle.kts"], MODULES, BUILDS.get), [":core:protocol:jvmTest"])


if __name__ == "__main__":
    unittest.main()
