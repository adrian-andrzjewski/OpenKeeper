# Pathfinding connection-object reuse

This branch preserves an exploratory variant of `MapIndexedGraph`. It caches
the per-node connection arrays and reuses mutable connection objects in those
arrays. It is kept separate from the consolidated production branch.

## Validation

The full test task, including `MapIndexedGraphTest`, and
`mapIndexedGraphBenchmark` passed with tasks forced to rerun on 2026-11-10.
The unit test checks per-source array and edge reuse, path reconstruction, and
terrain changes. The benchmark builds deterministic 85x85 maps and checks
path checksums in three profiles. Repeated measurements were:

| Scenario | First run time change | Forced rerun time change | Allocation change |
| --- | ---: | ---: | ---: |
| Open long route | +8.8% | -0.2% | -71.5% |
| Alternating wall detour | +1.3% | +5.4% | -72.1% |
| Unreachable across wall | -3.6% | +2.4% | -71.6% |

Path checksums matched in all six measurements. The allocation reduction was
consistent; search-time changes varied around parity and did not show a
repeatable speedup. The benchmark covers only these deterministic route
profiles and does not measure whole-game frame time or garbage-collection
effects in a live level.

Because the candidate mutates cached connection objects, its lifetime and
aliasing contract with pathfinder parent records also needs review beyond these
checksum profiles. Keep this as a research candidate until both the runtime
effect and connection lifetime are established.

Run with `.\gradlew.bat --no-daemon test mapIndexedGraphBenchmark`.
