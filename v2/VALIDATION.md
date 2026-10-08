# Development validation — 2026-10-08

## Scope and conclusion

This is a runnable **research/review prototype**, not a validated automatic honeydew counter.
The accepted direction remains Java 21 + Swing, ImageJ/AnimalTracker, CPU-only rule-based
vision, and independent human review. The old Python application and YOLO experiment
are preserved; neither is the new detector.

The current algorithm identifier is `dual-background-context/0.1`.
No thresholds depend on the input filename, event hint or a particular timestamp.

## Final short-video run

Outputs are in the ignored local directory `artifacts/v2/final/`.
The development harness creates seed projects, result JSON and candidate CSV for each clip.
Seed body/head/tail landmarks were provisionally selected by the developer and still need
human confirmation.

| Short clip | Decoded frames | Last image PTS (s) | Candidates | Unassessed time (s) | Wall time (s) |
|---|---:|---:|---:|---:|---:|
| 1 | 520 | 17.378 | 4 | 0.335 | 40.311 |
| 2 | 153 | 5.090 | 8 | 0.335 | 11.535 |
| 3, blurred | 385 | 12.858 | 7 | 0.335 | 28.661 |
| 4, negative with second insect | 1,252 | 41.889 | 0 | 0.335 | 104.993 |
| 5, negative with strong body motion | 325 | 10.849 | 0 | 4.386 | 20.165 |

Times are measured on this workstation, with some build/test work occurring alongside analysis;
they are not a controlled hardware benchmark. Last image PTS is not the container's exact duration.
Observed benchmark peak working set near the end of the run was approximately **785 MiB**.
The process exited cleanly. This does not establish long-run memory stability or whole-system RAM
use when Fiji and the desktop preview are also open.

Every clip has approximately 0.335 s of initial background warm-up.
Clip 5 additionally loses reliable tracking from approximately **6.80 to 10.85 s**.
That interval requires a new manual anchor and must not be counted as a verified negative.

Clip 1 candidates include the visible droplet near 11.9 s, confirmed in the rendered source-frame
review. Other candidate tracks can be parts of the same event or artifacts.
Clip 2 has candidate groups around 1.94–2.48 s and 2.88–3.65 s.
The blurred third clip remains particularly uncertain. None of these candidate counts is an
excretion count, and no event-level recall/precision is claimed.

## What the experiments established

- The first single-body-coordinate background produced many false components when body
  stabilization made a stationary leaf appear to move.
- Pose diagnostics on clip 1 found stable head/tail coordinates before the body movement;
  the main problem was not uncontrolled tracking drift.
- Bounded background adaptation alone did not solve the problem: it reduced stale duration
  but could fragment components into additional tracks.
- Requiring change in **both scene and body coordinates** eliminated candidates on negative
  clip 4 in this development set.
- Maintaining context tracks for objects first seen away from the abdomen avoids reclassifying
  an approaching object as a droplet born at the tip.
- These are development observations, **not a comparison against the legacy Python detector**
  and not evidence of held-out accuracy.

## Engineering verification

**17 tests pass: 16 pipeline tests and 1 native video integration test, no failures/skips.**

They exercise real ImageJ/AnimalTracker components, robust motion fitting and native OpenCV flow,
bright/dark synthetic droplets, unchanged backgrounds, incoming distractors, stationary scene
texture under moving body coordinates, clipped ROI corners, background-ghost expiry, timestamp
discontinuities, manual-reset boundaries, project persistence and changed-source rejection.

The video integration test writes a real MP4 with unequal presentation-time gaps, decodes it,
checks the actual PTS values and seeks to a known timestamp. It does not infer time from nominal FPS.

The Swing panel was rendered offscreen with a real candidate selected and visually inspected
(`artifacts/v2/review-final.png`). Native mouse/keyboard acceptance testing remains to be done.
Local Fiji installation, its launcher path, the pinned AnimalTracker plugin and setup checksums
were verified. Interactive Fiji plugin dialogs have not been acceptance-tested.

## Acceptance gates still open

The provisional goals of **at least 95% recall** and **at most 20 false candidates/hour**
are **not established**. About 42 seconds of one negative clip cannot establish an hourly
false-positive rate. Unassessed time and uncertain reference events must be reported separately.

Before making an accuracy claim:

1. Have the expert verify the body/head/tail anchors and annotate complete intervals, not only
   the generated candidates. Define event start/end semantics and uncertainty explicitly.
2. Add precise attachment/growth/separation/departure evidence and event-level duplicate handling.
   The current output is persistent component-track hypotheses, not a complete biological event model.
3. Improve identity and deformation handling; inspect and correct the tracking failure in clip 5.
   Do not silently reacquire a nearby second insect.
4. Determine whether shorts are excerpts of any long recording. Group related material into the same
   split; freeze parameters before held-out evaluation.
5. Evaluate event-level recall, unmatched false candidates/hour, duplicate candidates/event,
   unknown coverage, speed and memory on complete independently reviewed long intervals.
6. Run long-duration memory testing and native desktop/Fiji interaction acceptance tests before
   preparing a distributable installer.

No long recording was used for tuning or validation during this implementation run.
