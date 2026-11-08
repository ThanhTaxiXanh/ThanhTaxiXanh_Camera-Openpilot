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

    def test_measured_radial_distortion_matches_independent_reference(self):
        d = fixture()
        d["distortion_brown_conrady"] = dict(k1=0.2, k2=0, p1=0, p2=0, k3=0)
        # Independently calculate the radial displacement from measured
        # normalized coordinates, not from the implementation's projection.
        for marker in d["markers"]:
            x = marker["world_m"][0] / 10
            y = marker["world_m"][1] / 10
            scale = 1 + 0.2 * (x*x + y*y)
            marker["observed_px"] = [320 + 400*x*scale, 240 + 400*y*scale]
        self.assertTrue(audit(d)["eligible_for_independent_review"])
        d["distortion_brown_conrady"]["k1"] = 0
        self.assertGreater(audit(d)["rms_error_px"], 0)

    def test_tangential_distortion_changes_projection(self):
        d = fixture()
        d["distortion_brown_conrady"] = dict(k1=0, k2=0, p1=0.1, p2=0, k3=0)
        # x=0.1, y=0.05: delta x=2*p1*x*y=0.001,
        # delta y=p1*(x*x+3*y*y)=0.00175.
        d["markers"][5]["observed_px"] = [360.4, 260.7]
        self.assertAlmostEqual(audit(d)["markers"][5]["error_px"], 0, places=8)

    def test_malformed_distortion_fails_closed(self):
        for distortion in ({"k1": 0.1}, dict(k1=0, k2=0, p1=0, p2=0, k3=float("nan")),
                           dict(k1=0, k2=0, p1=True, p2=0, k3=0)):
            d = fixture()
            d["distortion_brown_conrady"] = distortion
            with self.assertRaises(ValueError):
                audit(d)

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
