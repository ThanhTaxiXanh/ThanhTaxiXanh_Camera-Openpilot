#!/usr/bin/env python3
"""Deterministic positive and fail-closed tests for A500S marker geometry."""
import copy
import unittest

from a500s_marker_reprojection_audit import audit


def fixture():
    # Six measured-style points in front of a synthetic camera. Not field evidence.
    points = [[-1, -0.5, 10], [0, -0.5, 10], [1, -0.5, 10],
              [-1, 0.5, 10], [0, 0.5, 10], [1, 0.5, 10]]
    return dict(image_width=640, image_height=480, fx=400, fy=400, cx=320, cy=240,
                rotation_world_to_camera=[[1, 0, 0], [0, 1, 0], [0, 0, 1]],
                translation_world_to_camera_m=[0, 0, 0],
                markers=[dict(id=str(i), world_m=p,
                              observed_px=[320 + 40*p[0], 240 + 40*p[1]])
                         for i, p in enumerate(points)])


class MarkerAuditTest(unittest.TestCase):
    def test_exact_geometry(self):
        result = audit(fixture())
        self.assertTrue(result["eligible_for_independent_review"])
        self.assertEqual(result["rms_error_px"], 0)

    def test_outlier_cannot_hide_in_rms(self):
        d = fixture()
        d["markers"][0]["observed_px"][0] += 7
        result = audit(d)
        self.assertFalse(result["eligible_for_independent_review"])
        self.assertGreater(result["max_error_px"], 6)

    def test_bad_rotation_rejected(self):
        d = fixture()
        d["rotation_world_to_camera"][0][0] = 2
        with self.assertRaises(ValueError):
            audit(d)

    def test_reflection_rejected(self):
        d = fixture()
        d["rotation_world_to_camera"][0][0] = -1
        with self.assertRaises(ValueError):
            audit(d)

    def test_marker_behind_camera_rejected(self):
        d = fixture()
        d["markers"][0]["world_m"][2] = -1
        with self.assertRaises(ValueError):
            audit(d)

    def test_duplicate_id_rejected(self):
        d = fixture()
        d["markers"][1]["id"] = "0"
        with self.assertRaises(ValueError):
            audit(d)

    def test_nonfinite_and_bool_rejected(self):
        for value in (float("nan"), float("inf"), True):
            d = fixture()
            d["fx"] = value
            with self.assertRaises(ValueError):
                audit(d)

    def test_insufficient_markers_rejected(self):
        d = fixture()
        d["markers"].pop()
        with self.assertRaises(ValueError):
            audit(d)

    def test_pixel_outside_image_rejected(self):
        d = fixture()
        d["markers"][0]["observed_px"] = [-1, 100]
        with self.assertRaises(ValueError):
            audit(d)


if __name__ == "__main__":
    unittest.main()
