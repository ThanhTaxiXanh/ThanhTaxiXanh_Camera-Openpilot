#!/usr/bin/env python3
"""Fail-closed, standard-library two-gate acceptance ledger validator.

A = independently evidenced engineering readiness: >=18 of 20.
B = independently reviewed field verification: all mandatory 14,15,16,17,20.
CI can validate provenance metadata STRUCTURE, not authenticity of device
recordings, model rights or real-world measurements. Human audit is required.
"""
import argparse
import datetime
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "docs" / "acceptance" / "two_gate_ledger.json"
MANDATORY = (14, 15, 16, 17, 20)
REQUIRED_FIELD = {
    14: {"DEVICE_YUV", "LIVE_INFERENCE", "VALIDATION_PROTOCOL"},
    15: {"OPTICAL_CALIBRATION", "MODEL_AXIS_REFERENCE", "VALIDATION_PROTOCOL"},
    16: {"LICENSE_PROOF", "GROUND_TRUTH", "VALIDATION_PROTOCOL"},
    17: {"LIVE_HUD", "METRIC_REFERENCE", "VALIDATION_PROTOCOL"},
    20: {"FIVE_MINUTE_TRACE", "GLASS_TO_GLASS", "VALIDATION_PROTOCOL"},
}
VALID_ENGINEERING = {"NOT_READY", "CODED", "PASS"}
VALID_FIELD = {"NOT_REQUIRED", "NOT_VERIFIED", "PASS"}
VALID_OFFLINE_KINDS = {
    "CI_UNIT", "CI_BUILD", "CI_PACKAGE", "OFFLINE_INTEGRATION",
    "AUTHORIZED_REFERENCE", "REVIEWED_PROTOCOL",
}


class GateError(ValueError):
    pass


def require(ok, message):
    if not ok:
        raise GateError(message)


def check_ref(ref, root):
    require(isinstance(ref, str) and len(ref.strip()) > 5, "Empty evidence reference")
    if ref.startswith("https://github.com/ThanhTaxiXanh/ThanhTaxiXanh_Camera-Openpilot/"):
        return
    path = Path(ref)
    require(not path.is_absolute() and ".." not in path.parts, "Unsafe evidence path: " + ref)
    require((root / path).is_file(), "Evidence path missing: " + ref)


def validate(data, root=ROOT):
    require(isinstance(data, dict), "Ledger must be an object")
    require(data.get("schema_version") == 1, "Unsupported ledger schema")
    require(data.get("engineering_target_pass") == 18, "Engineering target is fixed at 18/20")
    require(data.get("field_mandatory_ids") == list(MANDATORY),
            "Mandatory field list may not be weakened")
    criteria = data.get("criteria")
    require(isinstance(criteria, list) and len(criteria) == 20,
            "Exactly 20 acceptance items required")
    require([c.get("id") for c in criteria] == list(range(1, 21)),
            "Acceptance IDs must be complete and ordered 1..20")
    engineering_pass = 0
    field_pass = 0

    for item in criteria:
        cid = item["id"]
        require(isinstance(item.get("name"), str) and item["name"].strip(),
                f"#{cid}: missing requirement")
        a, b = item.get("engineering"), item.get("field")
        require(isinstance(a, dict) and isinstance(b, dict),
                f"#{cid}: both gates required")
        require(a.get("status") in VALID_ENGINEERING,
                f"#{cid}: invalid engineering state")
        require(b.get("status") in VALID_FIELD,
                f"#{cid}: invalid field state")
        require(isinstance(a.get("next_required"), str) and a["next_required"].strip(),
                f"#{cid}: missing next evidence objective")
        ae, be = a.get("evidence"), b.get("evidence")
        require(isinstance(ae, list) and isinstance(be, list),
                f"#{cid}: evidence must be arrays")

        for entry in ae:
            require(isinstance(entry, dict) and entry.get("kind") in VALID_OFFLINE_KINDS,
                    f"#{cid}: unsupported offline evidence")
            check_ref(entry.get("ref"), root)
        if a["status"] == "PASS":
            require(bool(ae), f"#{cid}: PASS without engineering evidence")
            if cid not in (1, 2):
                require(any(x["kind"] in {"CI_UNIT", "OFFLINE_INTEGRATION",
                                          "AUTHORIZED_REFERENCE"} for x in ae),
                        f"#{cid}: green CI alone cannot certify this requirement")
            engineering_pass += 1

        if cid not in MANDATORY:
            require(b["status"] == "NOT_REQUIRED" and not be,
                    f"#{cid}: nonmandatory field item must be NOT_REQUIRED")
            continue
        require(b["status"] != "NOT_REQUIRED",
                f"#{cid}: mandatory field evidence cannot be waived")
        if b["status"] == "PASS":
            require(a["status"] == "PASS",
                    f"#{cid}: physical PASS cannot precede engineering readiness")
            kinds = set()
            for entry in be:
                require(isinstance(entry, dict), f"#{cid}: field evidence malformed")
                kind = entry.get("kind")
                require(kind in REQUIRED_FIELD[cid],
                        f"#{cid}: unexpected field evidence kind")
                check_ref(entry.get("ref"), root)
                require(re.fullmatch(r"[a-fA-F0-9]{64}", str(entry.get("sha256", ""))) is not None,
                        f"#{cid}: field artifact requires real SHA-256")
                ts = entry.get("recorded_at_utc")
                require(isinstance(ts, str) and ts.endswith("Z"),
                        f"#{cid}: field evidence requires UTC timestamp")
                try:
                    datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
                except ValueError as ex:
                    raise GateError(f"#{cid}: malformed UTC timestamp") from ex
                reviewer = entry.get("independent_reviewer")
                require(isinstance(reviewer, str) and len(reviewer.strip()) >= 3
                        and reviewer.lower() not in ("tbd", "unknown", "self"),
                        f"#{cid}: no independent reviewer")
                kinds.add(kind)
            require(kinds == REQUIRED_FIELD[cid],
                    f"#{cid}: missing mandatory field evidence: " +
                    str(sorted(REQUIRED_FIELD[cid] - kinds)))
            field_pass += 1
        else:
            require(not be,
                    f"#{cid}: pending field checks must not masquerade as verified artifacts")

    return {
        "engineering_pass": engineering_pass,
        "engineering_total": 20,
        "engineering_target": 18,
        "engineering_ready": engineering_pass >= 18,
        "field_pass": field_pass,
        "field_total": len(MANDATORY),
        "field_ready": field_pass == len(MANDATORY),
        "final_90_verified": engineering_pass >= 18 and field_pass == len(MANDATORY),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ledger", type=Path, default=LEDGER)
    args = parser.parse_args()
    try:
        results = validate(json.loads(args.ledger.read_text(encoding="utf-8")), ROOT)
    except (GateError, json.JSONDecodeError, OSError) as error:
        print("TWO_GATE_LEDGER_INVALID:", error, file=sys.stderr)
        return 1
    print(f"GATE_A_ENGINEERING={results['engineering_pass']}/20 "
          f"(target >=18) READY={results['engineering_ready']}")
    print(f"GATE_B_FIELD={results['field_pass']}/{results['field_total']} "
          f"MANDATORY_READY={results['field_ready']}")
    print(f"FINAL_90_VERIFIED={results['final_90_verified']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
