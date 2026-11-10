/*
 * Copyright (C) 2014-2026 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package toniarts.openkeeper.view.map;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.BatchNode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A deterministic jME scene-graph traversal benchmark for terrain tile placeholders. */
public final class SparseTerrainNodesBenchmark {

    private static final int WIDTH = 85;
    private static final int HEIGHT = 85;
    private static final int PAGE_SIZE = 8;
    private static final int WARMUP_PASSES = 2_000;
    private static final int SAMPLES = 21;
    private static final int PASSES_PER_SAMPLE = 100;
    private static final int EDIT_WARMUPS = 3;
    private static final int EDIT_SAMPLES = 15;
    private static volatile long sink;

    private SparseTerrainNodesBenchmark() {
    }

    public static void main(String[] args) {
        DesktopAssetManager assetManager = new DesktopAssetManager(true);
        Materials materials = Materials.create(assetManager);
        if (args.length > 0 && ("dense-only".equals(args[0]) || "sparse-only".equals(args[0]))) {
            boolean sparse = "sparse-only".equals(args[0]);
            com.sun.management.ThreadMXBean allocationBean = allocationBean();
            long threadId = Thread.currentThread().getId();
            long allocatedBefore = allocationBean == null ? -1 : allocationBean.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            SceneModel scene = SceneModel.build(sparse, materials);
            long buildNanos = System.nanoTime() - start;
            long allocatedBytes = allocationBean == null ? -1
                    : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
            if (scene.batchGeometryCount() != 363 || scene.batchVertexCount() != 32_084) {
                throw new IllegalStateException("Unexpected synthetic batch output");
            }
            System.out.printf(Locale.ROOT,
                    "mode=%s buildMs=%.2f allocatedMiB=%.2f tileNodes=%d sceneNodes=%d "
                            + "batchGeometries=%d vertices=%d%n",
                    sparse ? "sparse" : "dense", buildNanos / 1_000_000d,
                    allocatedBytes / (1024d * 1024d), scene.tileNodeCount, scene.sceneNodeCount(),
                    scene.batchGeometryCount(), scene.batchVertexCount());
            return;
        }
        boolean sparseFirst = args.length > 0 && "sparse-first".equals(args[0]);
        long denseBuildNanos;
        long sparseBuildNanos;
        SceneModel dense;
        SceneModel sparse;
        if (sparseFirst) {
            long start = System.nanoTime();
            sparse = SceneModel.build(true, materials);
            sparseBuildNanos = System.nanoTime() - start;
            start = System.nanoTime();
            dense = SceneModel.build(false, materials);
            denseBuildNanos = System.nanoTime() - start;
        } else {
            long start = System.nanoTime();
            dense = SceneModel.build(false, materials);
            denseBuildNanos = System.nanoTime() - start;
            start = System.nanoTime();
            sparse = SceneModel.build(true, materials);
            sparseBuildNanos = System.nanoTime() - start;
        }

        if (dense.batchGeometryCount() != sparse.batchGeometryCount()
                || dense.batchVertexCount() != sparse.batchVertexCount()) {
            throw new IllegalStateException("Dense and sparse batches have different geometry output");
        }

        System.out.printf(Locale.ROOT,
                "map=%dx%d pageSize=%d pages=%d samples=%d passesPerSample=%d batchGeometries=%d vertices=%d%n",
                WIDTH, HEIGHT, PAGE_SIZE, dense.pages.size(), SAMPLES, PASSES_PER_SAMPLE,
                dense.batchGeometryCount(), dense.batchVertexCount());
        System.out.printf(Locale.ROOT,
                "dense tileNodes=%d sceneNodes=%d buildMs=%.2f sparse tileNodes=%d sceneNodes=%d "
                        + "buildMs=%.2f parity=PASS%n",
                dense.tileNodeCount, dense.sceneNodeCount(), denseBuildNanos / 1_000_000d,
                sparse.tileNodeCount, sparse.sceneNodeCount(),
                sparseBuildNanos / 1_000_000d);

        dense.updateLogicalState(WARMUP_PASSES);
        sparse.updateLogicalState(WARMUP_PASSES);
        long[] denseNanos = new long[SAMPLES];
        long[] sparseNanos = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            if ((sample & 1) == 0) {
                denseNanos[sample] = measure(dense, PASSES_PER_SAMPLE);
                sparseNanos[sample] = measure(sparse, PASSES_PER_SAMPLE);
            } else {
                sparseNanos[sample] = measure(sparse, PASSES_PER_SAMPLE);
                denseNanos[sample] = measure(dense, PASSES_PER_SAMPLE);
            }
        }
        System.out.printf(Locale.ROOT,
                "updateLogicalState medianUsPerPass dense=%.3f sparse=%.3f change=%+.1f%% "
                        + "denseRangeUs=%.3f-%.3f sparseRangeUs=%.3f-%.3f%n",
                median(denseNanos) / (double) PASSES_PER_SAMPLE / 1_000d,
                median(sparseNanos) / (double) PASSES_PER_SAMPLE / 1_000d,
                percentChange(median(denseNanos), median(sparseNanos)),
                min(denseNanos) / (double) PASSES_PER_SAMPLE / 1_000d,
                max(denseNanos) / (double) PASSES_PER_SAMPLE / 1_000d,
                min(sparseNanos) / (double) PASSES_PER_SAMPLE / 1_000d,
                max(sparseNanos) / (double) PASSES_PER_SAMPLE / 1_000d);

        for (UpdateScenario scenario : updateScenarios()) {
            long[] denseEditNanos = new long[EDIT_SAMPLES];
            long[] sparseEditNanos = new long[EDIT_SAMPLES];
            for (int i = 0; i < EDIT_WARMUPS; i++) {
                dense.rebuildTiles(scenario.tiles());
                sparse.rebuildTiles(scenario.tiles());
            }
            for (int i = 0; i < EDIT_SAMPLES; i++) {
                if ((i & 1) == 0) {
                    denseEditNanos[i] = measureRebuild(dense, scenario);
                    sparseEditNanos[i] = measureRebuild(sparse, scenario);
                } else {
                    sparseEditNanos[i] = measureRebuild(sparse, scenario);
                    denseEditNanos[i] = measureRebuild(dense, scenario);
                }
            }
            if (dense.batchGeometryCount() != sparse.batchGeometryCount()
                    || dense.batchVertexCount() != sparse.batchVertexCount()) {
                throw new IllegalStateException("Edit changed dense/sparse batch parity");
            }
            System.out.printf(Locale.ROOT,
                    "%s tiles=%d rebatchMedianMs dense=%.3f sparse=%.3f change=%+.1f%% "
                            + "denseRangeMs=%.3f-%.3f sparseRangeMs=%.3f-%.3f%n",
                    scenario.name(), scenario.tiles().size(), median(denseEditNanos) / 1_000_000d,
                    median(sparseEditNanos) / 1_000_000d,
                    percentChange(median(denseEditNanos), median(sparseEditNanos)),
                    min(denseEditNanos) / 1_000_000d, max(denseEditNanos) / 1_000_000d,
                    min(sparseEditNanos) / 1_000_000d, max(sparseEditNanos) / 1_000_000d);
        }
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean extended)
                || !extended.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!extended.isThreadAllocatedMemoryEnabled()) {
            extended.setThreadAllocatedMemoryEnabled(true);
        }
        return extended;
    }

    private static long measure(SceneModel scene, int passes) {
        long start = System.nanoTime();
        scene.updateLogicalState(passes);
        return System.nanoTime() - start;
    }

    private static long measureRebuild(SceneModel scene, UpdateScenario scenario) {
        long start = System.nanoTime();
        scene.rebuildTiles(scenario.tiles());
        return System.nanoTime() - start;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long min(long[] values) {
        return Arrays.stream(values).min().orElseThrow();
    }

    private static long max(long[] values) {
        return Arrays.stream(values).max().orElseThrow();
    }

    private static double percentChange(long dense, long sparse) {
        return (sparse - dense) * 100d / dense;
    }

    private static List<UpdateScenario> updateScenarios() {
        return List.of(
                new UpdateScenario("single", expandedTiles(42, 42, 1, 1)),
                new UpdateScenario("room8", expandedTiles(31, 31, 8, 8)),
                new UpdateScenario("room16", expandedTiles(34, 34, 16, 16)),
                new UpdateScenario("room24", expandedTiles(30, 30, 24, 24)));
    }

    private static Set<Tile> expandedTiles(int x, int y, int width, int height) {
        Set<Tile> result = new HashSet<>();
        for (int tileY = Math.max(0, y - 1); tileY < Math.min(HEIGHT, y + height + 1); tileY++) {
            for (int tileX = Math.max(0, x - 1); tileX < Math.min(WIDTH, x + width + 1); tileX++) {
                result.add(new Tile(tileX, tileY));
            }
        }
        return result;
    }

    private record Tile(int x, int y) {
    }

    private record UpdateScenario(String name, Set<Tile> tiles) {
    }

    private record Materials(Material floor, Material wall, Material top) {

        private static Materials create(DesktopAssetManager assetManager) {
            return new Materials(create(assetManager, ColorRGBA.White),
                    create(assetManager, ColorRGBA.Gray), create(assetManager, ColorRGBA.LightGray));
        }

        private static Material create(DesktopAssetManager assetManager, ColorRGBA color) {
            Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setColor("Color", color);
            return material;
        }
    }

    private static final class SceneModel {

        private final Node root;
        private final List<BatchNode[]> pages;
        private final List<Node[][]> tileNodesByPage;
        private final int tileNodeCount;
        private final int pagesPerRow;
        private final boolean sparse;
        private final Materials materials;
        private final Quad mesh;

        private SceneModel(Node root, List<BatchNode[]> pages, List<Node[][]> tileNodesByPage,
                int tileNodeCount, int pagesPerRow, boolean sparse, Materials materials, Quad mesh) {
            this.root = root;
            this.pages = pages;
            this.tileNodesByPage = tileNodesByPage;
            this.tileNodeCount = tileNodeCount;
            this.pagesPerRow = pagesPerRow;
            this.sparse = sparse;
            this.materials = materials;
            this.mesh = mesh;
        }

        private static SceneModel build(boolean sparse, Materials materials) {
            int pagesPerRow = (WIDTH + PAGE_SIZE - 1) / PAGE_SIZE;
            int pagesPerColumn = (HEIGHT + PAGE_SIZE - 1) / PAGE_SIZE;
            Node root = new Node("terrain");
            List<BatchNode[]> pages = new ArrayList<>(pagesPerRow * pagesPerColumn);
            List<Node[][]> tileNodesByPage = new ArrayList<>(pagesPerRow * pagesPerColumn);
            int tileNodeCount = 0;
            Quad mesh = new Quad(1f, 1f);

            for (int pageY = 0; pageY < pagesPerColumn; pageY++) {
                for (int pageX = 0; pageX < pagesPerRow; pageX++) {
                    Node page = new Node(pageX + "_" + pageY);
                    BatchNode[] layers = {
                        new BatchNode("floor"),
                        new BatchNode("wall"),
                        new BatchNode("top")
                    };
                    Node[][] tileNodes = new Node[3][PAGE_SIZE * PAGE_SIZE];
                    for (BatchNode layer : layers) {
                        page.attachChild(layer);
                    }

                    for (int localY = 0; localY < PAGE_SIZE; localY++) {
                        for (int localX = 0; localX < PAGE_SIZE; localX++) {
                            int x = pageX * PAGE_SIZE + localX;
                            int y = pageY * PAGE_SIZE + localY;
                            boolean insideMap = x < WIDTH && y < HEIGHT;
                            boolean[] present = {
                                insideMap && floorPresent(x, y),
                                insideMap && wallPresent(x, y),
                                insideMap && topPresent(x, y)
                            };
                            for (int layerIndex = 0; layerIndex < layers.length; layerIndex++) {
                                if (!sparse || present[layerIndex]) {
                                    Node tile = new Node(x + "_" + y);
                                    if (present[layerIndex]) {
                                        tile.setLocalTranslation(x, y, layerIndex);
                                        tile.attachChild(createGeometry(layerIndex, mesh, materials));
                                    }
                                    layers[layerIndex].attachChild(tile);
                                    tileNodes[layerIndex][localY * PAGE_SIZE + localX] = tile;
                                    tileNodeCount++;
                                }
                            }
                        }
                    }

                    root.attachChild(page);
                    for (BatchNode layer : layers) {
                        layer.batch();
                    }
                    pages.add(layers);
                    tileNodesByPage.add(tileNodes);
                }
            }
            return new SceneModel(root, pages, tileNodesByPage, tileNodeCount, pagesPerRow,
                    sparse, materials, mesh);
        }

        private void rebuildTiles(Set<Tile> tiles) {
            Set<BatchNode> nodesNeedBatching = new HashSet<>();
            for (Tile tile : tiles) {
                int pageIndex = (tile.y() / PAGE_SIZE) * pagesPerRow + tile.x() / PAGE_SIZE;
                int tileIndex = (tile.y() % PAGE_SIZE) * PAGE_SIZE + tile.x() % PAGE_SIZE;
                BatchNode[] layers = pages.get(pageIndex);
                Node[][] pageTileNodes = tileNodesByPage.get(pageIndex);
                for (int layerIndex = 0; layerIndex < layers.length; layerIndex++) {
                    BatchNode layer = layers[layerIndex];
                    Node tileNode = pageTileNodes[layerIndex][tileIndex];
                    boolean hadGeometry = tileNode != null && !tileNode.getChildren().isEmpty();
                    if (hadGeometry) {
                        tileNode.removeFromParent();
                        pageTileNodes[layerIndex][tileIndex] = null;
                        tileNode = null;
                    }

                    boolean present = geometryPresent(layerIndex, tile.x(), tile.y());
                    if (tileNode == null && (!sparse || present)) {
                        tileNode = new Node(tile.x() + "_" + tile.y());
                        layer.attachChild(tileNode);
                        pageTileNodes[layerIndex][tileIndex] = tileNode;
                    }
                    if (present) {
                        tileNode.setLocalTranslation(tile.x(), tile.y(), layerIndex);
                        tileNode.attachChild(createGeometry(layerIndex, mesh, materials));
                    }
                    if (hadGeometry || present) {
                        nodesNeedBatching.add(layer);
                    }
                }
            }
            for (BatchNode node : nodesNeedBatching) {
                node.batch();
            }
            sink += nodesNeedBatching.size();
        }

        private void updateLogicalState(int passes) {
            for (int i = 0; i < passes; i++) {
                root.updateLogicalState(1f / 60f);
            }
            sink += passes;
        }

        private int sceneNodeCount() {
            return countNodes(root);
        }

        private int batchGeometryCount() {
            int result = 0;
            for (BatchNode[] page : pages) {
                for (BatchNode layer : page) {
                    for (Spatial child : layer.getChildren()) {
                        if (child instanceof Geometry geometry && !geometry.isGrouped()) {
                            result++;
                        }
                    }
                }
            }
            return result;
        }

        private long batchVertexCount() {
            long result = 0;
            for (BatchNode[] page : pages) {
                for (BatchNode layer : page) {
                    for (Spatial child : layer.getChildren()) {
                        if (child instanceof Geometry geometry && !geometry.isGrouped()) {
                            result += geometry.getMesh().getVertexCount();
                        }
                    }
                }
            }
            return result;
        }

        private static int countNodes(Spatial spatial) {
            int result = spatial instanceof Node ? 1 : 0;
            if (spatial instanceof Node node) {
                for (Spatial child : node.getChildren()) {
                    result += countNodes(child);
                }
            }
            return result;
        }

        private static boolean floorPresent(int x, int y) {
            return ((x * 13 + y * 7) % 10) < 7;
        }

        private static boolean wallPresent(int x, int y) {
            return ((x * 17 + y * 11) % 20) < 5;
        }

        private static boolean topPresent(int x, int y) {
            return ((x * 5 + y * 3) % 25) < 4;
        }

        private static boolean geometryPresent(int layer, int x, int y) {
            return switch (layer) {
                case 0 -> floorPresent(x, y);
                case 1 -> wallPresent(x, y);
                case 2 -> topPresent(x, y);
                default -> throw new IllegalArgumentException("Unknown layer " + layer);
            };
        }

        private static Geometry createGeometry(int layer, Quad mesh, Materials materials) {
            Geometry geometry = new Geometry("terrain", mesh);
            geometry.setMaterial(switch (layer) {
                case 0 -> materials.floor();
                case 1 -> materials.wall();
                case 2 -> materials.top();
                default -> throw new IllegalArgumentException("Unknown layer " + layer);
            });
            return geometry;
        }
    }
}
