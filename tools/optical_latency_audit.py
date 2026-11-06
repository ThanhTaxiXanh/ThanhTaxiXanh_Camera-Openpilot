"""Independent optical scene-to-screen timing audit for Gate B #20.

Input CSV columns: event_id,scene_ms,display_ms,scene_source,display_source.
Times must come from independently inspected optical recording, not RTP/decoder
timestamps. This tool does not certify the source of a recording.
"""
import csv
import math
import statistics
from pathlib import Path


def audit_csv(path, *, minimum_events=10, maximum_latency_ms=1500, minimum_duration_ms=300000):
    rows = list(csv.DictReader(Path(path).open(encoding="utf-8", newline="")))
    if len(rows) < minimum_events:
        raise ValueError("insufficient independently matched optical events")
    seen = set()
    latencies = []
    last_scene = last_display = -math.inf
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
        last_scene, last_display = scene, display
        latencies.append(display - scene)
    ordered = sorted(latencies)
    p95 = ordered[math.ceil(.95 * len(ordered)) - 1]
    duration_ms = last_scene - float(rows[0]["scene_ms"])
    return {
        "events": len(ordered),
        "median_ms": statistics.median(ordered),
        "p95_ms": p95,
        "maximum_ms": ordered[-1],
        "duration_scene_ms": duration_ms,
        "duration_over_five_minutes": duration_ms > minimum_duration_ms,
        "eligible_for_independent_field_review": duration_ms > minimum_duration_ms and p95 <= maximum_latency_ms,
        "within_latency_target": p95 <= maximum_latency_ms,
        "gate_b_20_pass": False,
        "note": "Requires independently reviewed >5-minute uninterrupted A500S stream, optical method and uncertainty; numeric audit alone never passes Gate B."
    }
