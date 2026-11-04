#!/usr/bin/env python3
"""Prints the JVM test tasks of the Gradle modules that a branch changes.

Run: python3 scripts/changed_test_tasks.py [base]

The base is origin/main if no base is given. The script compares HEAD and the work tree with the
merge base of HEAD and the base. scripts/validate.sh --ci runs the printed tasks again and again
on few CPUs, so that a test that depends on time fails locally and not first in CI.

The output is one task for each line, for example ":core:protocol:jvmTest". A change of the
build logic (gradle/, composite/, a root build file) prints "validateJvm": then all JVM tests run.
"""

import os
import re
import subprocess
import sys

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# A change of one of these paths can change each module.
BUILD_PATHS = ("gradle/", "composite/", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")

ALL_TESTS = "validateJvm"


def included_modules(settings_text):
    """Gives the module paths of the include(...) calls, for example ["core/ui", "validation"]."""
    return [path.lstrip(":").replace(":", "/") for path in re.findall(r'"(:[A-Za-z0-9_:.-]+)"', settings_text)]


def module_of(path, modules):
    """Gives the module that contains the file [path], or None. The longest module path wins."""
    best = None
    for module in modules:
        if path == module or path.startswith(module + "/"):
            if best is None or len(module) > len(best):
                best = module
    return best


def test_task(module, build_text):
    """Gives the JVM test task of [module] from its build file, or None for an Android-only module."""
    project = ":" + module.replace("/", ":")
    if "libs.plugins.kotlinMultiplatform" in build_text:
        return project + ":jvmTest"
    if "libs.plugins.kotlinJvm" in build_text:
        return project + ":test"
    return None


def changed_tasks(paths, modules, read_build):
    """Gives the sorted test tasks of the changed [paths].

    read_build gives the build file text of a module.
    """
    tasks = set()
    for path in paths:
        if path.startswith(BUILD_PATHS):
            return [ALL_TESTS]
        module = module_of(path, modules)
        if module is None:
            continue
        task = test_task(module, read_build(module))
        if task is not None:
            tasks.add(task)
    return sorted(tasks)


def changed_paths(base):
    """Gives the files that differ between the merge base with [base] and the work tree."""
    merge_base = subprocess.run(
        ["git", "merge-base", "HEAD", base], cwd=ROOT_DIR, check=True, capture_output=True, text=True
    ).stdout.strip()
    committed = subprocess.run(
        ["git", "diff", "--name-only", merge_base], cwd=ROOT_DIR, check=True, capture_output=True, text=True
    ).stdout.split()
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT_DIR, check=True, capture_output=True, text=True
    ).stdout.split()
    return sorted(set(committed + untracked))


def main(argv):
    base = argv[1] if len(argv) > 1 else "origin/main"
    with open(os.path.join(ROOT_DIR, "settings.gradle.kts"), encoding="utf-8") as settings:
        modules = included_modules(settings.read())

    def read_build(module):
        with open(os.path.join(ROOT_DIR, module, "build.gradle.kts"), encoding="utf-8") as build:
            return build.read()

    for task in changed_tasks(changed_paths(base), modules, read_build):
        print(task)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
