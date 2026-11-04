#!/usr/bin/env python3
"""Prints the Android SDK packages that the build needs, for sdkmanager.

Run: python3 scripts/android_sdk_packages.py

The versions come from gradle/libs.versions.toml, so that CI installs the same CMake and NDK that
the build asks for. A CI runner image can remove an old CMake or NDK at each update. AGP installs
the platform and the build tools, but it does not install CMake.
"""

import os
import re
import sys

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT_DIR, "gradle", "libs.versions.toml")

# The packages that do not have a version in the catalog.
FIXED_PACKAGES = ["platform-tools"]

# The sdkmanager package of each catalog version.
VERSIONED_PACKAGES = {"cmake": "cmake", "ndk": "ndk"}


def catalog_versions(text):
    """Gives the entries of the [versions] table of a version catalog as a dict."""
    versions = {}
    in_versions = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            in_versions = stripped == "[versions]"
            continue
        match = re.match(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"', stripped)
        if in_versions and match:
            versions[match.group(1)] = match.group(2)
    return versions


def sdk_packages(text):
    """Gives the sdkmanager packages for the version catalog text."""
    versions = catalog_versions(text)
    packages = list(FIXED_PACKAGES)
    for key, package in VERSIONED_PACKAGES.items():
        if key not in versions:
            raise ValueError(f"The version catalog has no '{key}' version.")
        packages.append(f"{package};{versions[key]}")
    return packages


def main():
    with open(CATALOG, encoding="utf-8") as catalog:
        print(" ".join(sdk_packages(catalog.read())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
