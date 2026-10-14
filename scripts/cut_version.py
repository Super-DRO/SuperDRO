#!/usr/bin/env python3
"""Calculate the next release tag of SuperDRO, and make the tag if necessary.

The version comes from the calendar, and the git tag is the single source of truth
(docs/versioning-and-release-plan.md). The form of the version is:

    <2-digit ISO week-year>.<ISO week 1-53>.<release count in that week, 1 or more>

The first release in ISO week 41 of 2026 is ``26.41.1``. The second release in that
week is ``26.41.2``. The count is the highest count of the tags of that week, plus 1.
The repository stores no counter.

The year has two digits, because the MSI tool of jpackage refuses a major part above
255. A four digit year ``2026.x.y`` cannot make a Windows package.

The workflow ``release.yml`` runs this script. The functions :func:`iso_week_year`,
:func:`parse_tag`, :func:`next_count`, :func:`compute_version` and :func:`version_code`
get all their inputs as arguments. Thus a unit test can examine them with no git
repository (``tests/test_cut_version.py``).

Usage::

    cut_version.py --dry-run        # write the next version and tag, change nothing
    cut_version.py --print          # write only the next version
    cut_version.py --tag [--ref R]  # make the annotated tag v<version> on R (default HEAD)

``--tag`` makes only the local annotated tag. It writes ``version=``, ``tag=`` and
``version_code=`` to ``$GITHUB_OUTPUT``. The workflow pushes the tag and starts the
build, so that the credentials and the permissions stay in the workflow.
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import subprocess
import sys

# A release tag: v<year>.<week>.<count>. Each part is an integer with no zero at the start.
# Each other tag (a dev suffix, a different form) gives None, so it does not change the count.
TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")

TAG_PREFIX = "v"


def iso_week_year(today: datetime.date) -> tuple[int, int]:
    """Give ``(2-digit ISO week-year, ISO week)`` for *today*.

    The function uses the ISO-8601 week-year (``date.isocalendar()``, ``date +%G``). The
    ISO week-year goes with the ISO week. Near the new year it is not the calendar year.
    For example, 2025-12-29 is in week 1 of the ISO week-year 2026. With the calendar
    year (``%Y``), that day gives ``25.1`` after ``25.52``, which is a lower version.
    """
    iso = today.isocalendar()
    return iso[0] % 100, iso[1]


def parse_tag(tag: str) -> tuple[int, int, int] | None:
    """Give the three integers of ``v<year>.<week>.<count>``, or None for a different form."""
    match = TAG_RE.match(tag.strip())
    if match is None:
        return None
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def next_count(tags: "list[str] | tuple[str, ...]", year: int, week: int) -> int:
    """Give the next count for (*year*, *week*): the highest count plus 1, or 1.

    The function ignores a tag of a different form, and a tag of a different week or year.
    """
    counts = [
        count
        for parsed in (parse_tag(tag) for tag in tags)
        if parsed is not None
        for (tag_year, tag_week, count) in [parsed]
        if tag_year == year and tag_week == week
    ]
    return max(counts, default=0) + 1


def compute_version(tags: "list[str] | tuple[str, ...]", today: datetime.date) -> str:
    """Give the next version, for example ``26.41.1``."""
    year, week = iso_week_year(today)
    count = next_count(tags, year, week)
    return f"{year}.{week}.{count}"


def version_code(version: str) -> int:
    """Give the integer version code that Android needs (``versionCode``).

        code = (year % 100) * 100000 + week * 1000 + count

    ``26.41.1`` gives ``2641001``. The function ignores the ``-dev+...`` part. The
    minimum is 1, because Android needs ``versionCode >= 1``.

    ``projectVersionCode()`` in ``composite/src/main/kotlin/org/superdro/composite/util/Version.kt``
    uses the same formula. Keep the two the same.
    """
    base = version.split("-", 1)[0]
    parts = base.split(".")

    def component(index: int) -> int:
        try:
            return int(parts[index])
        except (IndexError, ValueError):
            return 0

    year, week, count = component(0), component(1), component(2)
    code = (year % 100) * 100000 + week * 1000 + count
    return max(code, 1)


def tag_name(version: str) -> str:
    return f"{TAG_PREFIX}{version}"


# --------------------------------------------------------------------------------------
# git and CI functions. The unit tests do not examine these functions.
# --------------------------------------------------------------------------------------


def _run_git(args: "list[str]") -> str:
    """Run git. When git fails, stop with one line that contains the error text of git."""
    try:
        result = subprocess.run(
            ["git", *args],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        raise SystemExit("git is not installed or not on PATH")
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip() or f"exit status {error.returncode}"
        raise SystemExit(f"git {' '.join(args)} failed: {detail}")
    return result.stdout.strip()


def existing_tags() -> "list[str]":
    """Give all tags of the repository that start with ``v``."""
    out = _run_git(["tag", "--list", f"{TAG_PREFIX}*"])
    return [line.strip() for line in out.splitlines() if line.strip()]


def create_tag(version: str, ref: str) -> str:
    """Make the annotated tag ``v<version>`` on *ref*, and give the name of the tag."""
    tag = tag_name(version)
    if tag in existing_tags():
        raise SystemExit(f"refusing to create {tag}: it already exists")
    _run_git(["tag", "-a", tag, "-m", f"Release {tag}", ref])
    return tag


def _emit_github_output(version: str, tag: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    with open(output_path, "a", encoding="utf-8") as handle:
        handle.write(f"version={version}\n")
        handle.write(f"tag={tag}\n")
        handle.write(f"version_code={version_code(version)}\n")


def _print_next(version: str, tag: str) -> None:
    print(f"Next version: {version}")
    print(f"Next tag:     {tag}")
    print(f"versionCode:  {version_code(version)}")


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="write the next version and tag; change nothing")
    mode.add_argument("--print", dest="print_only", action="store_true", help="write only the next version")
    mode.add_argument("--tag", action="store_true", help="make the annotated tag locally (no push)")
    parser.add_argument("--ref", default="HEAD", help="the commit of the tag with --tag (default: HEAD)")
    args = parser.parse_args(argv)

    tags = existing_tags()
    today = datetime.datetime.now(datetime.timezone.utc).date()
    version = compute_version(tags, today)
    tag = tag_name(version)

    if args.print_only:
        print(version)
        return 0

    if args.tag:
        created = create_tag(version, args.ref)
        _emit_github_output(version, created)
        print(f"Created tag {created} at {args.ref}")
        return 0

    # --dry-run, and also a call with no mode: change nothing.
    _print_next(version, tag)
    return 0


if __name__ == "__main__":
    sys.exit(main())
