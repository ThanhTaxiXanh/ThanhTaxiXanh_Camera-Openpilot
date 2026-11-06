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
            w.writerow(("event_id", "scene_ms", "display_ms", "scene_source", "display_source"))
            w.writerows(rows)

    def valid(self):
        return [(str(i), str(i * 1000), str(i * 1000 + 950), "optical", "optical") for i in range(11)]

    def test_valid_observations_are_not_automatic_gate_pass(self):
        self.write(self.valid())
        report = audit_csv(self.path)
        self.assertEqual(report["p95_ms"], 950)
        self.assertFalse(report["gate_b_20_pass"])
        self.assertFalse(report["duration_over_five_minutes"])
        self.assertFalse(report["eligible_for_independent_field_review"])

    def test_long_trace_is_review_eligible_but_never_auto_passes(self):
        rows = [(str(i), str(i * 1000), str(i * 31000 + 950), "optical", "optical") for i in range(311)]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertTrue(report["duration_over_five_minutes"])
        self.assertTrue(report["eligible_for_independent_field_review"])
        self.assertFalse(report["gate_b_20_pass"])

    def test_exact_five_minutes_is_insufficient(self):
        rows = [(str(i), str(i * 1000), str(i * 30000 + 950), "optical", "optical") for i in range(301)]
        self.write(rows)
        report = audit_csv(self.path)
        self.assertFalse(report["duration_over_five_minutes"])

    def test_long_trace_with_high_p95_is_not_review_eligible(self):
        rows = [(str(i), str(i * 31000), str(i * 31000 + 1700), "optical", "optical") for i in range(311)]
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
