"""Pure Python tests for the CI gate's fail-closed accounting rules."""
import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from verify_two_gate import GateError, LEDGER, validate  # noqa: E402


class TwoGateAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads(LEDGER.read_text(encoding="utf-8"))

    def test_real_baseline_is_seven_engineering_and_no_field_proof(self):
        result = validate(self.data)
        self.assertEqual(7, result["engineering_pass"])
        self.assertEqual(0, result["field_pass"])
        self.assertFalse(result["final_90_verified"])

    def test_code_only_does_not_advance_engineering_counter(self):
        self.data["criteria"][13]["engineering"]["status"] = "CODED"
        self.assertEqual(7, validate(self.data)["engineering_pass"])

    def test_green_build_alone_cannot_promote_non_build_requirement(self):
        item = self.data["criteria"][13]
        item["engineering"]["status"] = "PASS"
        item["engineering"]["evidence"] = [{
            "kind": "CI_BUILD",
            "ref": "https://github.com/ThanhTaxiXanh/ThanhTaxiXanh_Camera-Openpilot/actions/runs/41967248957",
        }]
        with self.assertRaises(GateError):
            validate(self.data)

    def test_mandatory_field_not_waivable(self):
        self.data["criteria"][13]["field"]["status"] = "NOT_REQUIRED"
        with self.assertRaises(GateError):
            validate(self.data)

    def test_mandatory_field_requires_prior_engineering_and_artifacts(self):
        item = self.data["criteria"][13]
        item["field"]["status"] = "PASS"
        with self.assertRaises(GateError):
            validate(self.data)

    def test_can_never_lower_eighteen_target_or_remove_mandatory(self):
        self.data["engineering_target_pass"] = 7
        with self.assertRaises(GateError):
            validate(self.data)
        self.data["engineering_target_pass"] = 18
        self.data["field_mandatory_ids"] = [14, 20]
        with self.assertRaises(GateError):
            validate(self.data)

    def test_missing_evidence_file_fails(self):
        item = self.data["criteria"][8]
        item["engineering"]["evidence"][0]["ref"] = "app/src/test/does-not-exist.kt"
        with self.assertRaises(GateError):
            validate(self.data)

    def test_duplicate_or_missing_criterion_fails(self):
        self.data["criteria"][19]["id"] = 19
        with self.assertRaises(GateError):
            validate(self.data)

    def test_self_review_cannot_complete_field_evidence(self):
        item = self.data["criteria"][13]
        item["engineering"]["status"] = "PASS"
        item["engineering"]["evidence"] = [{
            "kind": "OFFLINE_INTEGRATION",
            "ref": "app/src/test/java/vn/thanhtaxixanh/camera/ai/A500sOfflineVisionIntegrationTest.kt"
        }]
        item["field"]["status"] = "PASS"
        item["field"]["evidence"] = [
            {"kind": kind, "ref": "docs/TWO_GATE_ACCEPTANCE.md",
             "sha256": "0"*64, "recorded_at_utc": "2026-11-05T10:00:00Z",
             "independent_reviewer": "self"}
            for kind in ("DEVICE_YUV", "LIVE_INFERENCE", "VALIDATION_PROTOCOL")
        ]
        with self.assertRaises(GateError):
            validate(self.data)


if __name__ == "__main__":
    unittest.main()
