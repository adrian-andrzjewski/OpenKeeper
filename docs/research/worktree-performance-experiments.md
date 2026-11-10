# Worktree performance experiments

Date: 2026-11-10. These deterministic microbenchmarks preserve work from
separate experiment worktrees. They are research results and are not part of
the consolidated production branch.

## Reused A* connection arrays

The candidate caches each node's `Array<Connection>` and clears it when the
pathfinder asks for that node again. Across three deterministic path profiles,
the path checksums matched the original implementation and measured allocation
fell by 28.8%, 30.2%, and 28.9%. The first run measured median path-time
regressions of 16.4%, 6.7%, and 10.4%. A forced rerun on 2026-11-10 measured
15.1%, 3.9%, and 6.0% regressions, with the same allocation reductions. The
candidate is therefore retained for study but not used in production: lower
allocation did not offset slower path generation in any scenario.

Run with `.\gradlew.bat --no-daemon mapIndexedGraphBenchmark`.

## Terrain deformation baseline

The benchmark uses an 85x85 map with 29,241 vertices and checks the output
checksum. The first recorded cold median was 51.239 ms and 126.478 MiB
allocated; the warm median was 4.113 ms and 2.457 MiB. A forced rerun measured
73.774 ms cold (53.329–94.764 ms range) and 4.187 ms warm (4.110–4.596 ms
range), with the same allocation totals and checksum. This establishes a
repeatable baseline for future deformer work; it is not itself a production
optimization.

Run with `.\gradlew.bat --no-daemon terrainDeformerBenchmark`.

## Sparse terrain tile nodes

On an 85x85 synthetic map with 8x8 pages, dense and sparse variants produced
matching batch geometry: 363 geometries and 32,084 vertices. In the first
record, the sparse graph used 8,021 nodes instead of 23,232, and scene
construction measured 127.26 ms instead of 209.55 ms. A forced rerun measured
149.48 ms sparse versus 250.31 ms dense, with the same geometry parity and node
counts. State traversal was 1.2% faster in the rerun, and rebatch medians were
5.4–12.2% faster across the four edits; the measured ranges overlapped. The
prototype uses a synthetic placeholder scene and does not establish a
production traversal win, so it remains exploratory.

Run with `.\gradlew.bat --no-daemon sparseTerrainNodesBenchmark`.

## Limits

These measurements isolate individual CPU or allocation costs. They are not
whole-game FPS results, and none of the three candidates was promoted to the
consolidated production branch.
