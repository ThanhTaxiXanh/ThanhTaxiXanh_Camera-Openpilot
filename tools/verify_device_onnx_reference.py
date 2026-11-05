#!/usr/bin/env python3
"""Independent CPU replay of an Android-generated TTXREF01, NOT ADAS validation.

Requires numpy and onnxruntime (same packages as CI fixture).
Run offline against an authorized model and device-exported reference.
Do not upload raw tensors/camera frames to public services.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

MAGIC = b"TTXREF01"
TENSOR_SHAPE = (1, 12, 128, 256)
INPUT_FLOATS = 1 * 12 * 128 * 256
OUTPUT_FLOATS = 1576
SIZE = 40 + (2 * INPUT_FLOATS + OUTPUT_FLOATS) * 4
CHANNEL_SIZE = 128 * 256


def check(condition, description):
    if not condition:
        raise ValueError(description)


def analyze(model_path: Path, reference_path: Path):
    check(model_path.is_file() and 1024 < model_path.stat().st_size <= 128 * 1024 * 1024,
          "Model missing or outside safe 1KiB..128MiB bounds")
    check(reference_path.is_file() and reference_path.stat().st_size == SIZE,
          "Reference file must be exactly TTXREF01 fixed size")
    raw = reference_path.read_bytes()
    check(raw[:8] == MAGIC, "Wrong TTXREF01 header")
    model_sha = hashlib.sha256(model_path.read_bytes()).digest()
    check(raw[8:40] == model_sha, "Model SHA256 does not match TTXREF01")

    # This is big-endian IEEE754 data, not native little-endian Android memory.
    flat = np.frombuffer(raw, dtype=">f4", offset=40).astype(np.float32)
    check(len(flat) == 2 * INPUT_FLOATS + OUTPUT_FLOATS, "Wrong float count")
    check(bool(np.isfinite(flat).all()), "Nonfinite tensor or output data")
    inputs = []
    for i in range(2):
        start = i * INPUT_FLOATS
        tensor = flat[start:start + INPUT_FLOATS].reshape(TENSOR_SHAPE)
        check(bool((tensor >= 16.0).all()), "Input tensor below studio byte minimum")
        # Current packing convention: in each six-channel group, 4 Y channels,
        # then two chroma channels. Validity is *format* only, not image parity.
        for channel in range(12):
            high = 235.0 if channel % 6 < 4 else 240.0
            check(bool((tensor[0, channel] <= high).all()),
                  "Input exceeds studio channel maximum")
        inputs.append(tensor)
    expected = flat[2 * INPUT_FLOATS:].reshape((1, OUTPUT_FLOATS))

    session = ort.InferenceSession(
        str(model_path), providers=["CPUExecutionProvider"])
    actual_inputs = {p.name: (p.type, p.shape) for p in session.get_inputs()}
    actual_outputs = {p.name: (p.type, p.shape) for p in session.get_outputs()}
    check(actual_inputs == {
        "img": ("tensor(float)", list(TENSOR_SHAPE)),
        "big_img": ("tensor(float)", list(TENSOR_SHAPE)),
    }, "ONNX input contract mismatch")
    check(actual_outputs == {
        "vout_pass": ("tensor(float)", [1, OUTPUT_FLOATS])
    }, "ONNX output contract mismatch")
    actual = session.run(["vout_pass"], {
        "img": inputs[0], "big_img": inputs[1]
    })[0]
    check(actual.shape == (1, OUTPUT_FLOATS) and actual.dtype == np.float32,
          "ONNX Runtime returned wrong output type or shape")
    check(bool(np.isfinite(actual).all()), "Nonfinite CPU ONNX output")
    abs_diff = np.abs(actual.astype(np.float64) - expected.astype(np.float64))
    within = abs_diff <= 0.001 + 0.001 * np.abs(expected.astype(np.float64))
    failures = int(np.count_nonzero(~within))
    return {
        "format": "TTX-DEVICE-CPU-PARITY-01",
        "model_sha256": model_sha.hex(),
        "reference_file_sha256": hashlib.sha256(raw).hexdigest(),
        "img_float32_be_sha256": hashlib.sha256(
            raw[40:40 + INPUT_FLOATS * 4]).hexdigest(),
        "big_img_float32_be_sha256": hashlib.sha256(
            raw[40 + INPUT_FLOATS * 4:40 + 2 * INPUT_FLOATS * 4]).hexdigest(),
        "engine": "onnxruntime_cpu",
        "onnxruntime_version": ort.__version__,
        "compared_elements": OUTPUT_FLOATS,
        "mismatches": failures,
        "max_absolute_error": float(abs_diff.max()),
        "parity": "MATCHED_UNVERIFIED" if failures == 0 else "MISMATCH",
        "independent_runtime": True,
        "independent_ground_truth": False,
        "model_authorization_verified": False,
        "real_camera_provenance_verified": False,
        "calibration_verified": False,
        "field_gate_14": "NOT_VERIFIED",
        "field_gate_16": "NOT_VERIFIED",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = analyze(args.model, args.reference)
        payload = json.dumps(report, indent=2, sort_keys=True) + "\n"
        print(payload, end="")
        if args.report:
            args.report.write_text(payload, encoding="utf-8")
        return 0 if report["parity"] == "MATCHED_UNVERIFIED" else 2
    except (ValueError, RuntimeError, OSError, ort.capi.onnxruntime_pybind11_state.Fail) as error:
        print("TTX_DEVICE_CPU_PARITY_INVALID:", error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
