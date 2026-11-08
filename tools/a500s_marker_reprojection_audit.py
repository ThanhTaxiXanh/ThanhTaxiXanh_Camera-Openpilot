#!/usr/bin/env python3
"""Independent A500S pinhole-marker reprojection audit (no third-party dependencies).

Input JSON: image_width, image_height, fx, fy, cx, cy,
rotation_world_to_camera (3x3), translation_world_to_camera_m (3),
markers: [{id, world_m:[x,y,z], observed_px:[u,v]}].
Coordinate convention: camera x right, y down, z forward.
This audits measured correspondences; it does not estimate intrinsics or prove provenance.
"""
import argparse
import json
import math
from pathlib import Path


def _vec(value, length, name):
    if not isinstance(value, list) or len(value) != length:
        raise ValueError(f"{name}: expected {length} values")
    if any(isinstance(x, bool) or not isinstance(x, (float, int)) or not math.isfinite(x) for x in value):
        raise ValueError(f"{name}: values must be finite numbers")
    return [float(x) for x in value]


def audit(data, max_rms_px=3.0, max_error_px=6.0):
    if not isinstance(data, dict):
        raise ValueError("expected JSON object")
    width = data["image_width"]
    height = data["image_height"]
    if any(isinstance(v, bool) or not isinstance(v, int) or v <= 0 for v in (width, height)):
        raise ValueError("invalid image dimensions")
    fx, fy, cx, cy = (_vec([data[k]], 1, k)[0] for k in ("fx", "fy", "cx", "cy"))
    if fx <= 0 or fy <= 0 or not (0 <= cx < width and 0 <= cy < height):
        raise ValueError("invalid camera intrinsics")
    rotation = data["rotation_world_to_camera"]
    if not isinstance(rotation, list) or len(rotation) != 3:
        raise ValueError("rotation must be 3x3")
    r = [_vec(row, 3, "rotation row") for row in rotation]
    # Reject non-rigid transforms that could hide geometry errors.
    for i in range(3):
        for j in range(3):
            dot = sum(r[i][k] * r[j][k] for k in range(3))
            if abs(dot - (1.0 if i == j else 0.0)) > 1e-3:
                raise ValueError("rotation is not orthonormal")
    det = (r[0][0]*(r[1][1]*r[2][2]-r[1][2]*r[2][1])
           - r[0][1]*(r[1][0]*r[2][2]-r[1][2]*r[2][0])
           + r[0][2]*(r[1][0]*r[2][1]-r[1][1]*r[2][0]))
    if abs(det - 1.0) > 1e-3:
        raise ValueError("rotation must be proper (det=+1)")
    t = _vec(data["translation_world_to_camera_m"], 3, "translation")
    markers = data["markers"]
    if not isinstance(markers, list) or len(markers) < 6:
        raise ValueError("at least six independently measured markers required")
    seen = set()
    errors = []
    for marker in markers:
        key = marker["id"]
        if not isinstance(key, (str, int)) or isinstance(key, bool) or key in seen:
            raise ValueError("duplicate or invalid marker id")
        seen.add(key)
        xyz = _vec(marker["world_m"], 3, "world_m")
        observed = _vec(marker["observed_px"], 2, "observed_px")
        if not (0 <= observed[0] < width and 0 <= observed[1] < height):
            raise ValueError("observed marker outside image")
        camera = [sum(r[i][j]*xyz[j] for j in range(3)) + t[i] for i in range(3)]
        if camera[2] <= 0:
            raise ValueError("marker behind camera")
        projected = [fx*camera[0]/camera[2]+cx, fy*camera[1]/camera[2]+cy]
        errors.append({"id": key, "error_px": math.hypot(projected[0]-observed[0], projected[1]-observed[1]),
                       "projected_px": projected})
    rms = math.sqrt(sum(e["error_px"]**2 for e in errors)/len(errors))
    maximum = max(e["error_px"] for e in errors)
    return {"eligible_for_independent_review": rms <= max_rms_px and maximum <= max_error_px,
            "rms_error_px": rms, "max_error_px": maximum, "marker_count": len(errors),
            "markers": errors, "note": "Geometric consistency only; not physical A500S calibration or Gate B PASS"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("measurements", type=Path)
    args = parser.parse_args()
    try:
        result = audit(json.loads(args.measurements.read_text(encoding="utf-8")))
    except (ValueError, KeyError, TypeError, OSError) as exc:
        parser.exit(2, f"Invalid measurements: {exc}\n")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if not result["eligible_for_independent_review"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
