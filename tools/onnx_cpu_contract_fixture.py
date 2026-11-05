#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Generate a tiny ORIGINAL mathematical ONNX graph and independent CPU reference.

This is a model-execution CONTRACT fixture, NOT a driving/ADAS/ground-truth
model. No user data, xADAS weights, camera imagery or proprietary assets.
Source ONNX weights are only public arithmetic constants authored by TTX.
The reference is derived from the Python formula, NEVER from ORT output.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper

SHAPE = (1, 12, 128, 256)
INPUT_ELEMENTS = 393216
OUTPUT_ELEMENTS = 1576
MAGIC = b"TTXREF01"


def input_vectors():
    # Every channel is in 16..234, satisfying the production studio-byte
    # contract for luma 16..235 and chroma 16..240. Both inputs differ.
    positions = np.arange(INPUT_ELEMENTS, dtype=np.uint32)
    img = (16 + (positions * 7 + 11) % 219).astype(np.float32).reshape(SHAPE)
    wide = (16 + (positions * 13 + 47) % 219).astype(np.float32).reshape(SHAPE)
    return img, wide


def bias_vector():
    # 1,576 different positions; binary-exact fractions make parity
    # independent of BLAS scheduling and floating point fused operations.
    return ((np.arange(OUTPUT_ELEMENTS) % 29) * 0.125).astype(np.float32)


def original_graph():
    def info(name, shape):
        return helper.make_tensor_value_info(name, TensorProto.FLOAT, shape)

    def int_constant(name, values):
        return helper.make_tensor(name, TensorProto.INT64,
                                  [len(values)], values)

    init = [
        int_constant("slice_starts", [0]),
        int_constant("slice_ends", [OUTPUT_ELEMENTS]),
        int_constant("slice_axes", [1]),
        int_constant("slice_steps", [1]),
        helper.make_tensor("img_scale", TensorProto.FLOAT, [1], [0.25]),
        helper.make_tensor("wide_scale", TensorProto.FLOAT, [1], [0.75]),
        helper.make_tensor("index_bias", TensorProto.FLOAT,
                           [1, OUTPUT_ELEMENTS], bias_vector().tolist()),
    ]
    nodes = [
        helper.make_node("Flatten", ["img"], ["img_flat"], axis=1),
        helper.make_node("Flatten", ["big_img"], ["wide_flat"], axis=1),
        helper.make_node("Slice", ["img_flat", "slice_starts",
                                  "slice_ends", "slice_axes", "slice_steps"],
                         ["img_slice"]),
        helper.make_node("Slice", ["wide_flat", "slice_starts",
                                  "slice_ends", "slice_axes", "slice_steps"],
                         ["wide_slice"]),
        helper.make_node("Mul", ["img_slice", "img_scale"], ["img_scaled"]),
        helper.make_node("Mul", ["wide_slice", "wide_scale"], ["wide_scaled"]),
        helper.make_node("Add", ["img_scaled", "wide_scaled"], ["summed"]),
        helper.make_node("Add", ["summed", "index_bias"], ["vout_pass"]),
    ]
    graph = helper.make_graph(
        nodes, "TTX_original_synthetic_onnx_execution_fixture",
        [info("img", SHAPE), info("big_img", SHAPE)],
        [info("vout_pass", (1, OUTPUT_ELEMENTS))],
        initializer=init,
    )
    model = helper.make_model(
        graph,
        producer_name="TTX Camera AI - original synthetic contract, NOT ADAS",
        opset_imports=[helper.make_opsetid("", 13)],
        ir_version=10,
    )
    model.doc_string = (
        "Original MIT arithmetic-only graph. Not an autonomous driving "
        "model, not derived from xADAS/Openpilot weights and no safety output."
    )
    onnx.checker.check_model(model, full_check=True)
    payload = model.SerializeToString()
    assert len(payload) > 1024, "Production import guards reject tiny models"
    return payload


def independent_expected(img, wide):
    a = img.reshape(-1)[:OUTPUT_ELEMENTS]
    b = wide.reshape(-1)[:OUTPUT_ELEMENTS]
    return (np.float32(0.25) * a +
            np.float32(0.75) * b + bias_vector()).reshape(1, OUTPUT_ELEMENTS)


def sha(payload):
    return hashlib.sha256(payload).hexdigest()


def float32_be(data):
    assert data.dtype == np.float32
    assert np.isfinite(data).all()
    return np.asarray(data, dtype=">f4").tobytes(order="C")


def run(output):
    output.mkdir(parents=True, exist_ok=True)
    graph = original_graph()
    img, wide = input_vectors()
    expected = independent_expected(img, wide)
    assert img.shape == wide.shape == SHAPE
    assert expected.shape == (1, OUTPUT_ELEMENTS)
    assert np.isfinite(expected).all()

    session = ort.InferenceSession(graph, providers=["CPUExecutionProvider"])
    actual_inputs = {x.name: (x.type, x.shape) for x in session.get_inputs()}
    actual_outputs = {x.name: (x.type, x.shape) for x in session.get_outputs()}
    assert actual_inputs == {
        "img": ("tensor(float)", list(SHAPE)),
        "big_img": ("tensor(float)", list(SHAPE)),
    }, actual_inputs
    assert actual_outputs == {
        "vout_pass": ("tensor(float)", [1, OUTPUT_ELEMENTS])
    }, actual_outputs

    actual = session.run(["vout_pass"], {"img": img, "big_img": wide})[0]
    assert actual.shape == expected.shape and actual.dtype == np.float32
    assert np.isfinite(actual).all()
    np.testing.assert_allclose(actual, expected, rtol=0, atol=0,
                               err_msg="Real ONNX Runtime differs from independent formula")

    # Both input tensors MUST affect the output. A graph that accidentally
    # consumes only img or big_img would incorrectly pass a shape-only test.
    changed_img = img.copy()
    changed_img.reshape(-1)[0] += np.float32(1)
    changed_a = session.run(["vout_pass"], {
        "img": changed_img, "big_img": wide})[0]
    np.testing.assert_allclose(
        changed_a.reshape(-1)[0] - actual.reshape(-1)[0],
        np.float32(0.25), rtol=0, atol=0)
    changed_wide = wide.copy()
    changed_wide.reshape(-1)[1] += np.float32(1)
    changed_b = session.run(["vout_pass"], {
        "img": img, "big_img": changed_wide})[0]
    np.testing.assert_allclose(
        changed_b.reshape(-1)[1] - actual.reshape(-1)[1],
        np.float32(0.75), rtol=0, atol=0)

    # Wrong dimensions and missing tensors must actually FAIL in the
    # independent ORT CPU runtime, not merely be rejected by a dummy parser.
    for inputs in (
        {"img": img, "big_img": wide[:, :, :, :-1]},
        {"img": img},
    ):
        try:
            session.run(["vout_pass"], inputs)
        except Exception as failure:
            # Real native ORT errors have version-specific Python classes.
            # Do not catch a self-raised AssertionError as if it were ORT.
            assert not isinstance(failure, AssertionError)
        else:
            raise AssertionError("Malformed ONNX input unexpectedly succeeded")

    model_path = output / "TTX-original-contract.onnx"
    ref_path = output / "TTX-original-contract.ttxref"
    model_path.write_bytes(graph)
    raw_img = float32_be(img)
    raw_wide = float32_be(wide)
    raw_ref = float32_be(expected)
    reference = MAGIC + hashlib.sha256(graph).digest() + raw_img + raw_wide + raw_ref
    expected_bytes = 8 + 32 + (INPUT_ELEMENTS * 2 + OUTPUT_ELEMENTS) * 4
    assert len(reference) == expected_bytes, (len(reference), expected_bytes)
    ref_path.write_bytes(reference)
    assert ref_path.read_bytes()[:8] == MAGIC
    assert ref_path.read_bytes()[8:40] == hashlib.sha256(graph).digest()

    manifest = {
        "format": "TTX-ONNX-CPU-CONTRACT-01",
        "scope": "SYNTHETIC_EXECUTION_ONLY_NOT_ADAS_OR_11_PASS",
        "original_graph": True,
        "source": "tools/onnx_cpu_contract_fixture.py",
        "model_authorization": "original_ttx_mit_arithmetic_fixture_only",
        "reference_independence": "Python formula versus actual CPU ONNX Runtime",
        "android_onnx_execution": "NOT_TESTED_ON_DEVICE",
        "camera_ground_truth": "NONE",
        "onnx_version": onnx.__version__,
        "cpu_onnxruntime_version": ort.__version__,
        "numpy_version": np.__version__,
        "model_sha256": sha(graph),
        "img_float32_be_sha256": sha(raw_img),
        "big_img_float32_be_sha256": sha(raw_wide),
        "expected_float32_be_sha256": sha(raw_ref),
        "actual_float32_be_sha256": sha(float32_be(actual)),
        "reference_file_sha256": sha(reference),
        "input_shape": list(SHAPE),
        "output_shape": [1, OUTPUT_ELEMENTS],
        "max_abs_error": float(np.max(np.abs(expected-actual))),
        "mismatched_elements": int(np.count_nonzero(expected != actual)),
    }
    assert manifest["mismatched_elements"] == 0
    (output / "manifest.json").write_text(
        json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print("ACTUAL_ONNXRUNTIME_CPU_CONTRACT=PASS")
    print("MODEL_SHA256=" + manifest["model_sha256"])
    print("TTXREF01_SHA256=" + manifest["reference_file_sha256"])
    print("SCOPE=SYNTHETIC_ONLY_GATE_A_11_NOT_PASS_GATE_B_0_OF_5")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=Path)
    run(parser.parse_args().output_dir)
