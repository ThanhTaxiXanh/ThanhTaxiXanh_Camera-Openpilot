import csv
import tempfile
import unittest
from pathlib import Path

from optical_latency_audit import audit_csv


class OpticalLatencyAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "events.csv"

    def write(self, rows):
        with self.path.open("w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(("event_id", "scene_ms", "display_ms", "scene_source", "display_source", "uncertainty_ms"))
            w.writerows([(*row, "100") if len(row) == 5 else row for row in rows])

    def valid(self):
        return [(str(i), str(i * 1000), str(i * 1000 + 950), "optical", "optical") for i in range(11)]

    def test_valid_observations_are_not_automatic_gate_pass(self):
        self.write(self.valid())
        report = audit_csv(self.path)
        self.assertEqual(report["p95_ms"], 950)
        self.assertEqual(report["conservative_p95_ms"], 1050)
        self.assertFalse(report["gate_b_20_pass"])
        self.assertFalse(report["duration_over_five_minutes"])
        self.assertFalse(report["eligible_for_independent_field_review"])

    def test_long_trace_is_review_eligible_but_never_auto_passes(self):
        rows = [(str(i), str(i * 1000), str(i * 1000 + 950), "optical", "optical") for i in range(311)]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertTrue(report["duration_over_five_minutes"])
        self.assertTrue(report["eligible_for_independent_field_review"])
        self.assertFalse(report["gate_b_20_pass"])

    def test_short_display_timeline_cannot_pass_on_long_scene_timeline(self):
        # A delayed first display and gradually shrinking delay can make the
        # displayed observation span shorter than the physical scene capture.
        rows = [(str(i), str(i * 1000), str(i * 1000 + 10000 - 30 * i), "optical", "optical") for i in range(311)]
        self.write(rows)
        report = audit_csv(self.path, maximum_latency_ms=12000)
        self.assertGreater(report["duration_scene_ms"], 300000)
        self.assertLess(report["duration_display_ms"], 300000)
        self.assertFalse(report["duration_over_five_minutes"])
        self.assertFalse(report["eligible_for_independent_field_review"])

    def test_exact_five_minutes_is_insufficient(self):
        rows = [(str(i), str(i * 1000), str(i * 1000 + 950), "optical", "optical") for i in range(301)]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertFalse(report["duration_over_five_minutes"])

    def test_long_trace_with_high_p95_is_not_review_eligible(self):
        rows = [(str(i), str(i * 1000), str(i * 1000 + 1700), "optical", "optical") for i in range(311)]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertFalse(report["eligible_for_independent_field_review"])

    def test_sparse_events_cannot_claim_uninterrupted_coverage(self):
        rows = [(str(i), str(i * 31000), str(i * 31000 + 950), "optical", "optical") for i in range(11)]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertTrue(report["duration_over_five_minutes"])
        self.assertEqual(report["maximum_observed_event_gap_ms"], 31000)
        self.assertFalse(report["event_coverage_sufficient"])
        self.assertFalse(report["eligible_for_independent_field_review"])
        self.assertFalse(report["gate_b_20_pass"])

    def test_custom_event_gap_limit_is_explicit_and_fail_closed(self):
        rows = [(str(i), str(i * 31000), str(i * 31000 + 950), "optical", "optical") for i in range(11)]
        self.write(rows)
        with self.assertRaises(ValueError):
            audit_csv(self.path, maximum_event_gap_ms=0)
        report = audit_csv(self.path, maximum_event_gap_ms=31000)
        self.assertTrue(report["event_coverage_sufficient"])
        self.assertTrue(report["eligible_for_independent_field_review"])
        self.assertFalse(report["gate_b_20_pass"])

    def test_invalid_thresholds_cannot_force_review_eligibility(self):
        self.write(self.valid())
        for kwargs in (
            {"minimum_events": 0}, {"minimum_events": 1},
            {"minimum_events": 2.5}, {"minimum_events": True},
            {"maximum_latency_ms": 0}, {"maximum_latency_ms": -1},
            {"maximum_latency_ms": float("nan")},
            {"minimum_duration_ms": 0}, {"minimum_duration_ms": -300000},
            {"minimum_duration_ms": float("inf")},
            {"maximum_event_gap_ms": True}, {"maximum_event_gap_ms": float("nan")},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                audit_csv(self.path, **kwargs)

    def test_missing_required_csv_column_fails_closed(self):
        self.path.write_text("event_id,scene_ms,display_ms,scene_source\\n"
                             "a,0,950,optical\\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing required"):
            audit_csv(self.path, minimum_events=2)

    def test_uncertainty_can_push_p95_over_threshold(self):
        rows = [(*row, "600") for row in
                [(str(i), str(i * 1000), str(i * 1000 + 950), "optical", "optical") for i in range(311)]]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertEqual(report["p95_ms"], 950)
        self.assertEqual(report["conservative_p95_ms"], 1550)
        self.assertFalse(report["eligible_for_independent_field_review"])

    def test_missing_uncertainty_never_qualifies(self):
        rows = [(*row, "") for row in
                [(str(i), str(i * 1000), str(i * 1000 + 950), "optical", "optical") for i in range(311)]]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertFalse(report["optical_uncertainty_complete"])
        self.assertFalse(report["eligible_for_independent_field_review"])

    def test_negative_or_nonfinite_uncertainty_is_rejected(self):
        for bad in ("-1", "nan", "inf", "garbage"):
            with self.subTest(bad=bad):
                rows = [(*row, bad) for row in self.valid()]
                self.write(rows)
                with self.assertRaises(ValueError):
                    audit_csv(self.path)

    def test_rejects_nonoptical_source(self):
        rows = self.valid()
        rows[0] = ("0", "0", "950", "optical", "rtp")
        self.write(rows)
        with self.assertRaises(ValueError):
            audit_csv(self.path)

    def test_rejects_duplicate_event(self):
        rows = self.valid()
        rows[1] = ("0", "1000", "1950", "optical", "optical")
        self.write(rows)
        with self.assertRaises(ValueError):
            audit_csv(self.path)

    def test_rejects_negative_delay(self):
        rows = self.valid()
        rows[3] = ("3", "3000", "2999", "optical", "optical")
        self.write(rows)
        with self.assertRaises(ValueError):
            audit_csv(self.path)

    def test_rejects_short_trace(self):
        self.write(self.valid()[:2])
        with self.assertRaises(ValueError):
            audit_csv(self.path)


if __name__ == "__main__":
    unittest.main()
