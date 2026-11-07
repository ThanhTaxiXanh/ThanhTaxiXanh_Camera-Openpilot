"""Independent optical scene-to-screen timing audit for Gate B #20.

Input CSV columns: event_id,scene_ms,display_ms,scene_source,display_source,
uncertainty_ms (nonnegative combined optical timing uncertainty).
Times must come from independently inspected optical recording, not RTP/decoder
timestamps. This tool does not certify the source of a recording.
"""
import csv
import math
import statistics
from pathlib import Path


def audit_csv(path, *, minimum_events=10, maximum_latency_ms=1500, minimum_duration_ms=300000, maximum_event_gap_ms=5000):
    # Reject caller-controlled thresholds that could silently make an invalid
    # short/high-latency optical trace appear eligible for field review.
    if isinstance(minimum_events, bool) or not isinstance(minimum_events, int) or minimum_events < 10:
        raise ValueError("minimum_events must be an integer >= 10")
    for name, value in (("maximum_latency_ms", maximum_latency_ms),
                        ("minimum_duration_ms", minimum_duration_ms),
                        ("maximum_event_gap_ms", maximum_event_gap_ms)):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise ValueError(f"{name} must be positive and finite")
    # These are hard acceptance limits, not caller-adjustable targets. A stricter
    # experiment is allowed, but relaxed thresholds must never produce an
    # eligible-for-review result for a noncompliant five-minute recording.
    if maximum_latency_ms > 1500:
        raise ValueError("maximum_latency_ms cannot exceed the 1500 ms acceptance ceiling")
    if minimum_duration_ms < 300000:
        raise ValueError("minimum_duration_ms cannot be below five minutes")
    if maximum_event_gap_ms > 5000:
        raise ValueError("maximum_event_gap_ms cannot exceed the 5000 ms coverage ceiling")
    with Path(path).open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"event_id", "scene_ms", "display_ms", "scene_source", "display_source"}
        if not reader.fieldnames or not required.issubset(reader.fieldnames):
            raise ValueError("missing required optical event CSV columns")
        rows = list(reader)
    if len(rows) < minimum_events:
        raise ValueError("insufficient independently matched optical events")
    seen = set()
    latencies = []
    conservative_latencies = []
    uncertainty_complete = "uncertainty_ms" in reader.fieldnames
    last_scene = last_display = -math.inf
    maximum_observed_gap_ms = 0.0
    for row in rows:
        event_id = row["event_id"].strip()
        if not event_id or event_id in seen:
            raise ValueError("missing or duplicate event_id")
        seen.add(event_id)
        if row["scene_source"].strip() != "optical" or row["display_source"].strip() != "optical":
            raise ValueError("timestamps must both be optical observations")
        scene = float(row["scene_ms"])
        display = float(row["display_ms"])
        if not all(map(math.isfinite, (scene, display))):
            raise ValueError("non-finite timestamp")
        if scene <= last_scene or display <= last_display or display < scene:
            raise ValueError("nonmonotonic or negative optical delay")
        if latencies:
            maximum_observed_gap_ms = max(maximum_observed_gap_ms, scene - last_scene, display - last_display)
        last_scene, last_display = scene, display
        latencies.append(display - scene)
        if uncertainty_complete:
            raw_uncertainty = row.get("uncertainty_ms")
            if raw_uncertainty is None or not raw_uncertainty.strip():
                uncertainty_complete = False
            else:
                try:
                    uncertainty = float(raw_uncertainty)
                except ValueError as exc:
                    raise ValueError("invalid optical uncertainty") from exc
                if not math.isfinite(uncertainty) or uncertainty < 0:
                    raise ValueError("optical uncertainty must be finite and nonnegative")
                conservative_latencies.append(display - scene + uncertainty)
    ordered = sorted(latencies)
    p95 = ordered[math.ceil(.95 * len(ordered)) - 1]
    conservative_p95 = (sorted(conservative_latencies)[math.ceil(.95 * len(conservative_latencies)) - 1]
                        if uncertainty_complete else None)
    duration_ms = last_scene - float(rows[0]["scene_ms"])
    display_duration_ms = last_display - float(rows[0]["display_ms"])
    both_timelines_long_enough = duration_ms > minimum_duration_ms and display_duration_ms > minimum_duration_ms
    if not math.isfinite(maximum_event_gap_ms) or maximum_event_gap_ms <= 0:
        raise ValueError("maximum_event_gap_ms must be positive and finite")
    sufficient_coverage = maximum_observed_gap_ms <= maximum_event_gap_ms
    return {
        "events": len(ordered),
        "median_ms": statistics.median(ordered),
        "p95_ms": p95,
        "conservative_p95_ms": conservative_p95,
        "optical_uncertainty_complete": uncertainty_complete,
        "maximum_ms": ordered[-1],
        "duration_scene_ms": duration_ms,
        "duration_display_ms": display_duration_ms,
        "maximum_observed_event_gap_ms": maximum_observed_gap_ms,
        "event_coverage_sufficient": sufficient_coverage,
        "duration_over_five_minutes": both_timelines_long_enough,
        "eligible_for_independent_field_review": both_timelines_long_enough and sufficient_coverage and uncertainty_complete and conservative_p95 <= maximum_latency_ms,
        "within_latency_target": uncertainty_complete and conservative_p95 <= maximum_latency_ms,
        "gate_b_20_pass": False,
        "note": "Requires independently reviewed >5-minute uninterrupted A500S stream, optical method and uncertainty; uncertainty is an externally reviewed bound; both scene and display timelines must span >5 minutes; event coverage is only a sampling guard, not proof of no freezes; numeric audit alone never passes Gate B."
    }
