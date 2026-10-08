# Honeydew Review v2 (experimental)

A separate Windows desktop implementation. The legacy Python application is unchanged.

**Status:** working review/annotation prototype, not a validated automatic excretion counter.
The detector produces candidates. Neither its contrast score nor an accepted candidate is a
calibrated probability or an independent reference annotation. See [VALIDATION.md](VALIDATION.md).

## Start on this workstation

Double-click `START_V2.bat` in the repository root. The local JDK, native libraries,
Fiji and AnimalTracker are installed beside the project, not system-wide.

For a new checkout, use PowerShell from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File v2\setup.ps1 -IncludeFiji
powershell -ExecutionPolicy Bypass -File v2\build.ps1
.\START_V2.bat
```

Setup downloads official artifacts and checks pinned hashes. No administrator rights,
global PATH changes, Python environment, GPU, model weights or training are required.
The initial download is large (Fiji alone is about 705 MB). A later change to an upstream
mutable download URL intentionally causes a checksum failure; do not bypass that check.
Maven dependencies and extracted native libraries live in ignored `.tools/`.
Build currently targets Windows x64 and Java 21.

## Review workflow

1. **Open video**. The source video is never modified.
2. **Mark / correct insect here**: drag a tight body rectangle; click the head;
   click the abdomen tip where the droplet emerges. The marked frame's actual PTS is saved.
   Avoid including the second insect or the reflection in the body rectangle.
3. **Save project** to a JSON file outside the source-video directory if preferred.
4. **Analyze from start**. Analysis is streaming and cancellable. It does not store a video
   in memory. A new run replaces candidate decisions: save a separate project copy first.
5. Inspect **Candidates**. Select one, choose **Show candidate**, then use **Next frame**,
   the time entry or slow **Play / pause**. Accept, Reject or mark Uncertain.
   Playback is review-speed decoding, not audio-synchronized real-time playback.
6. Inspect **Unassessed**. These intervals are unknown, not negative. Seek to a suitable
   frame, mark the insect again and rerun. Tracking does not silently reacquire another insect.
7. Add **independent reference annotations** by entering precise start/end times and a label.
   Separately mark intervals reviewed **in full**, including frames with no candidates.
   Reviewing only candidate frames cannot establish recall.
8. Save the project and **Export CSV**. JSON retains anchors, references, reviewed intervals,
   coverage, settings and candidate decisions; CSV contains candidates only.

Green rectangle: manually marked body. Cyan point: head. Magenta point: abdomen tip.
Manual overlays are shown only on their exact source frame, not as an invented tracked pose.
Orange circle: a candidate's peak location. The small detail view retains source pixels.

**Open frame in Fiji** exports a TIFF under `artifacts/fiji/` and opens the local Fiji
installation. Manual anchor overlays are included when present on that frame.
The AnimalTracker plugin is installed in Fiji's `plugins/` folder.
Edits in Fiji are not automatically imported into the project. The production detector
uses the ImageJ API and AnimalTracker core directly; it does not automate plugin dialogs.

## Pipeline

- JavaCV/FFmpeg decodes original-resolution images sequentially, using presentation timestamps.
- OpenCV sparse Lucas–Kanade flow is checked forward/backward. Deterministic robust similarity
  consensus estimates body motion; bad geometry or insufficient feature support causes tracking loss.
- A bounded, body-aligned abdomen-tip patch is resampled. ImageJ performs grayscale conversion,
  interpolation and local smoothing.
- Adaptive temporal backgrounds in **both body and scene coordinates** estimate bright **and** dark
  changes using robust noise thresholds. This suppresses a static leaf's apparent movement after
  body alignment. Brief changes are protected; persistent changes are eventually absorbed.
- The real AnimalTracker `CoreTracker`, custom threshold adapter and `BaseBlobDetector`
  provide connected-component segmentation. AnimalTracker is not merely an unused dependency.
- Size/shape gates and one-to-one temporal association create tail-origin hypotheses. Objects
  first observed away from the tip remain context tracks, not newly born droplets when they approach it.
  Short-lived candidates require multiple observations; pending tracks are finalized at EOF.
- Background warm-up, unobservable tail regions and failed tracking are explicitly unassessed.

All thresholds are generic geometric/time/contrast assumptions; the detector has no video names
or known event timestamps in its logic. The five development seed anchors live only in the
test harness and need human confirmation.

This is the **first baseline**, not the full eventual state machine. Explicit droplet attachment,
growth, neck separation and departure evidence, stronger identity/occlusion handling and
held-out metrics remain future work. Abdominal deformation, moving leaves, reflections,
blur and a nearby second insect can still create false candidates.

## Command line

From the repository root (PowerShell):

```powershell
$java = '.tools\jdk\jdk-21.0.12.1+1\bin\java.exe'
$cp = 'v2/target/classes;v2/target/lib/*'
& $java -Xmx1024m '-Dorg.bytedeco.javacpp.cachedir=.tools/native-cache' -cp $cp org.konagy.honeydew.App --inspect D:\git\sources\short\1.mp4
& $java -Xmx1024m '-Dorg.bytedeco.javacpp.cachedir=.tools/native-cache' -cp $cp org.konagy.honeydew.App --analyze input.json new-result.json
```

The analysis output must not already exist. It also writes `new-result.json.csv`.
The saved source file must still have the recorded size and modification time. This check
detects common accidental substitutions; it is **not** a cryptographic content identity check.
Paths are absolute; automatic project relinking is not implemented.

The desktop launcher caps Java heap at 1 GB; native decoder/OpenCV allocations are additional.
Only one analysis and one preview decoder are active. OpenCV and FFmpeg decoder thread counts
are constrained. Long-duration memory/performance validation on the 4 GB target machine
is still required before batch use.

## Tests and development benchmark

`v2/build.ps1` runs the test suite before packaging. Tests cover the actual AnimalTracker adapter,
native optical flow, robust transform estimation, transient bright/dark candidates, static scenes,
tracking loss, correction boundaries, coverage intervals, project persistence, scene/body coordinate
confounding, incoming distractors and real variable-timestamp MP4 decode/seek.

```powershell
$java = '.tools\jdk\jdk-21.0.12.1+1\bin\java.exe'
$cp = 'v2/target/test-classes;v2/target/classes;v2/target/lib/*'
& $java -Xmx1024m '-Dorg.bytedeco.javacpp.cachedir=.tools/native-cache' -cp $cp org.konagy.honeydew.DevelopmentRun D:\git\sources\short artifacts\v2\my-run
```

Use a new output directory per experiment. The five short videos are development data,
not a held-out accuracy test. Do not tune on the long recordings until their source relationship
to the short clips and the validation split have been checked.

The review panel can be rendered offscreen using `--render PROJECT.json OUTPUT.png`.
This verifies layout but is not a substitute for native mouse/keyboard acceptance testing.

See [THIRD_PARTY.md](THIRD_PARTY.md) for dependency provenance.
