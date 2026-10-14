#!/usr/bin/env python3
"""Unit tests of the functions of scripts/cut_version.py.

Run: python3 -m unittest discover -s scripts/tests

The tests use no git repository and no network.
"""

import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cut_version import (  # noqa: E402
    compute_version,
    iso_week_year,
    next_count,
    parse_tag,
    tag_name,
    version_code,
)


class ParseTagTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(parse_tag("v26.41.1"), (26, 41, 1))
        self.assertEqual(parse_tag("v26.1.1"), (26, 1, 1))
        self.assertEqual(parse_tag("v26.53.12"), (26, 53, 12))

    def test_strips_whitespace(self):
        self.assertEqual(parse_tag("  v26.41.1\n"), (26, 41, 1))

    def test_invalid(self):
        self.assertIsNone(parse_tag("26.41.1"))           # no v
        self.assertIsNone(parse_tag("v26.41"))            # two parts
        self.assertIsNone(parse_tag("v26.41.1.4"))        # four parts
        self.assertIsNone(parse_tag("v26.41.1-dev+3-g"))  # dev suffix
        self.assertIsNone(parse_tag("pre-squash"))
        self.assertIsNone(parse_tag("vfoo"))
        self.assertIsNone(parse_tag(""))


class IsoWeekYearTests(unittest.TestCase):
    def test_usual_date(self):
        # 2026-10-13 is a Tuesday in ISO week 42 of the week-year 2026.
        self.assertEqual(iso_week_year(datetime.date(2026, 10, 13)), (26, 42))

    def test_first_week_of_the_year(self):
        # 2026-01-01 is a Thursday: ISO week 1 of 2026.
        self.assertEqual(iso_week_year(datetime.date(2026, 1, 1)), (26, 1))

    def test_last_week_of_the_year(self):
        # The Thursday of the week of 2026-12-28 is 2026-12-31: ISO week 53 of 2026.
        self.assertEqual(iso_week_year(datetime.date(2026, 12, 28)), (26, 53))

    def test_new_year_day_in_the_week_year_before(self):
        # 2027-01-01 is a Friday. The Thursday of its week is in 2026, so the ISO
        # week-year is 2026 and the week is 53. The calendar year 2027 gives 27.53,
        # and then 27.1 comes after it as a lower version.
        year, week = iso_week_year(datetime.date(2027, 1, 1))
        self.assertEqual((year, week), (26, 53))

    def test_december_day_in_the_next_week_year(self):
        # 2025-12-29 (Monday) starts ISO week 1 of 2026.
        self.assertEqual(iso_week_year(datetime.date(2025, 12, 29)), (26, 1))


class NextCountTests(unittest.TestCase):
    def test_no_tags_gives_one(self):
        self.assertEqual(next_count([], 26, 41), 1)

    def test_highest_count_plus_one(self):
        self.assertEqual(next_count(["v26.41.1"], 26, 41), 2)
        self.assertEqual(next_count(["v26.41.1", "v26.41.2"], 26, 41), 3)

    def test_uses_the_highest_count_not_the_number_of_tags(self):
        # A deleted tag leaves a gap. The count goes after the highest count.
        self.assertEqual(next_count(["v26.41.1", "v26.41.4"], 26, 41), 5)

    def test_ignores_other_weeks(self):
        self.assertEqual(next_count(["v26.40.5"], 26, 41), 1)

    def test_ignores_other_years(self):
        self.assertEqual(next_count(["v25.41.9"], 26, 41), 1)

    def test_count_above_nine_is_a_number(self):
        # As text, "10" comes before "9". As a number, 10 is the highest.
        self.assertEqual(next_count(["v26.41.9", "v26.41.10"], 26, 41), 11)

    def test_ignores_tags_of_a_different_form(self):
        tags = ["v26.41.1", "garbage", "v26.41.x", "v26.41.2", "", "pre-squash"]
        self.assertEqual(next_count(tags, 26, 41), 3)


class ComputeVersionTests(unittest.TestCase):
    def test_first_release_of_the_week(self):
        self.assertEqual(compute_version([], datetime.date(2026, 10, 13)), "26.42.1")

    def test_second_release_of_the_week(self):
        self.assertEqual(compute_version(["v26.42.1"], datetime.date(2026, 10, 13)), "26.42.2")

    def test_week_one_has_no_leading_zero(self):
        self.assertEqual(compute_version([], datetime.date(2026, 1, 1)), "26.1.1")


# The same values as VersionTest in composite. The script gives the code of the tag, and
# projectVersionCode() gives the code of the build, so the two must agree.
class VersionCodeTests(unittest.TestCase):
    def test_formula(self):
        self.assertEqual(version_code("26.41.1"), 2641001)
        self.assertEqual(version_code("26.29.1"), 2629001)

    def test_new_year_increases(self):
        self.assertEqual(version_code("27.1.1"), 2701001)
        self.assertEqual(version_code("26.52.9"), 2652009)
        self.assertGreater(version_code("27.1.1"), version_code("26.52.9"))

    def test_week_53(self):
        self.assertEqual(version_code("26.53.1"), 2653001)

    def test_increases_in_a_week(self):
        self.assertGreater(version_code("26.41.2"), version_code("26.41.1"))

    def test_increases_across_weeks(self):
        self.assertGreater(version_code("26.41.1"), version_code("26.40.9"))

    def test_ignores_dev_metadata(self):
        self.assertEqual(version_code("26.41.1-dev+7-gabc123"), 2641001)

    def test_minimum_is_one(self):
        self.assertEqual(version_code("0.0.0-dev"), 1)
        self.assertEqual(version_code("0.0.0-dev+gabc123"), 1)
        self.assertEqual(version_code(""), 1)
        self.assertEqual(version_code("garbage"), 1)

    def test_missing_part_is_zero(self):
        self.assertEqual(version_code("26.41"), 2641000)

    def test_four_digit_year_gives_the_same_code(self):
        self.assertEqual(version_code("2026.29.1"), version_code("26.29.1"))

    def test_stays_below_the_play_limit(self):
        self.assertLess(version_code("99.53.999"), 2_100_000_000)


class TagNameTests(unittest.TestCase):
    def test_prefix(self):
        self.assertEqual(tag_name("26.41.1"), "v26.41.1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
