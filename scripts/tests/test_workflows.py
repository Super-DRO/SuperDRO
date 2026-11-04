#!/usr/bin/env python3
"""Rules of the GitHub workflows.

Run: python3 -m unittest discover -s scripts/tests
"""

import os
import re
import unittest

WORKFLOWS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), ".github", "workflows"
)


def workflows():
    for name in sorted(os.listdir(WORKFLOWS_DIR)):
        if name.endswith((".yml", ".yaml")):
            with open(os.path.join(WORKFLOWS_DIR, name), encoding="utf-8") as workflow:
                yield name, workflow.read()


class WorkflowTests(unittest.TestCase):
    def test_the_ubuntu_runners_have_a_fixed_release(self):
        # ubuntu-latest changes the image on a day that GitHub selects. The change from 24.04 to
        # 26.04 removed the CMake of the build. A new release must come in a pull request.
        for name, text in workflows():
            labels = re.findall(r"^\s*-?\s*(?:runs-on|os):\s*(\S+)", text, re.MULTILINE)
            self.assertTrue(labels, name)
            for label in labels:
                if label.startswith("ubuntu"):
                    self.assertRegex(label, r"^ubuntu-\d\d\.\d\d$", f"{name}: {label}")


if __name__ == "__main__":
    unittest.main()
