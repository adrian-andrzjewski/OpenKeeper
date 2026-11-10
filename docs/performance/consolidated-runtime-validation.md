# Consolidated Runtime Validation

Validation was performed on 2026-11-10 against the consolidated tree using
jMonkeyEngine 3.10.0-beta3 and Java 25.0.4.1. Full builds and the automated
test suite passed after the upstream gameplay merge and the gameplay UI
fallback fix.

## Renderer parity

On an RTX 2060 SUPER with NVIDIA driver 617.42, the OpenGL and Vulkan smoke
renderers produced the same capture SHA-256
`52164a111cb504dd33b8f213e3bb9dbeb7110f3d52445cd394e2af9e556a0fb6`; 0 of
307,200 pixels differed. The run covered the smoke scene and its post-process
path. Frame intervals are host pacing measurements, not GPU timestamps.

An earlier in-game backend sample used Level1 at 1920x1080, VSync off, 0x
MSAA, a 20-second warmup, and 40 seconds of sampling per run. Three OpenGL
runs averaged 143.656 FPS; three Vulkan/ANGLE runs averaged 142.602 FPS, a
-0.73% throughput delta. The measured intervals include CPU work and
presentation pacing. Four of the six launches logged a caught
`ConcurrentModificationException` in `GameController` during startup warmup;
all timed captures completed. These runs used the earlier Vulkan backend
worktree, before the final upstream gameplay merge, and are not GPU timing or
visual parity evidence. Raw logs, CSV captures, and the run report are retained
under `build/preserved-evidence/vulkan-game-benchmark/` in the local checkout.

## Gameplay run

The local Dungeon Keeper II installation allowed the late-campaign OpenGL
workload to run at 1920x1080. It used Level20, seed 42, 100 combat creatures,
40 imps, a 3-second warmup, and 20 seconds of measurement per run. Before the
latest upstream gameplay merge, the three-run session reported
average FPS of 33.696, 39.865, and 38.723 (median 38.723; mean 37.428;
run-to-run CV 7.2%). All three visual checkpoints passed in each run.

A follow-up run after adding a fallback for discovered traps without GUI icons
completed without the prior `PlayerScreenController.createIcon` null-pointer
exception. It passed cp00 and cp02 but failed cp01 with 0.793 SSIM, 4.498 MAE,
and a 0.363 mismatch ratio. The camera pose is fixed per checkpoint, but the
live game state can change during play; treat these image checks as
intermittent until repeated runs establish stability. After the latest
upstream gameplay merge, a no-reference-update run reported 35.078 average
FPS; cp00 and cp02 passed, while cp01 failed with 0.701 SSIM, 6.025 MAE, and a
0.414 mismatch ratio. The 3-run FPS values predate the upstream gameplay merge
and describe that earlier source state. The official performance JSON baseline
was not changed. The checked-in visual references were refreshed for jME
3.10.0-beta3 before the upstream merge and remain unchanged because the
mid-run checkpoint is intermittent.

Vulkan also completed a Level1 timing sample in the earlier backend worktree.
Full-game Vulkan visual parity, video playback, and GPU timestamp measurements
remain outstanding.
