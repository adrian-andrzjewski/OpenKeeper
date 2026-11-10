/*
 * Copyright (C) 2014-2026 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package toniarts.openkeeper.game.navigation.pathfinding;

import com.badlogic.gdx.ai.pfa.Connection;
import com.badlogic.gdx.ai.pfa.DefaultConnection;
import com.badlogic.gdx.ai.pfa.DefaultGraphPath;
import com.badlogic.gdx.ai.pfa.GraphPath;
import com.badlogic.gdx.ai.pfa.indexed.IndexedAStarPathFinder;
import com.badlogic.gdx.ai.pfa.indexed.IndexedGraph;
import com.badlogic.gdx.utils.Array;
import com.simsilica.es.EntityComponent;
import com.simsilica.es.EntityId;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import toniarts.openkeeper.game.controller.IMapController;
import toniarts.openkeeper.game.logic.IEntityPositionLookup;
import toniarts.openkeeper.game.map.AbstractMapTileInformation;
import toniarts.openkeeper.game.map.IMapDataInformation;
import toniarts.openkeeper.game.map.IMapTileController;
import toniarts.openkeeper.game.map.IMapTileInformation;
import toniarts.openkeeper.tools.convert.map.Terrain;
import toniarts.openkeeper.tools.convert.map.TerrainFixtures;
import toniarts.openkeeper.utils.Point;

/**
 * Deterministic CPU benchmark for map path searches and graph expansion.
 * Run in a fresh JVM for each baseline/candidate sample.
 */
public final class MapIndexedGraphBenchmark {

    private static final int WIDTH = 85;
    private static final int HEIGHT = 85;
    private static final int WARMUP_BATCHES = 4;
    private static final int MEASURED_BATCHES = 9;
    private static final int PATHS_PER_BATCH = 30;
    private static final int OWNER_ID = 3;
    private static volatile long checksumSink;

    private MapIndexedGraphBenchmark() {
    }

    public static void main(String[] args) {
        com.sun.management.ThreadMXBean allocationBean = allocationBean();
        for (Scenario scenario : scenarios()) {
            measure(scenario, allocationBean);
        }
    }

    private static void measure(Scenario scenario, com.sun.management.ThreadMXBean allocationBean) {
        Fixture fixture = new Fixture(scenario.solid());
        INavigable navigable = new BenchmarkNavigable();
        MapIndexedGraph candidateGraph = new MapIndexedGraph(fixture.mapController(), fixture.entityPositions());
        candidateGraph.setPathFindable(navigable);
        BaselineGraph baselineGraph = new BaselineGraph(fixture.mapController(), fixture.entityPositions());
        baselineGraph.setPathFindable(navigable);
        IndexedAStarPathFinder<IMapTileInformation> candidateFinder = new IndexedAStarPathFinder<>(candidateGraph, false);
        IndexedAStarPathFinder<IMapTileInformation> baselineFinder = new IndexedAStarPathFinder<>(baselineGraph, false);
        MapDistance heuristic = new MapDistance();
        IMapTileController start = fixture.mapData.getTile(scenario.startX(), scenario.startY());
        IMapTileController end = fixture.mapData.getTile(scenario.endX(), scenario.endY());
        GraphPath<IMapTileInformation> baselinePath = new DefaultGraphPath<>();
        GraphPath<IMapTileInformation> candidatePath = new DefaultGraphPath<>();

        for (int batch = 0; batch < WARMUP_BATCHES; batch++) {
            if ((batch & 1) == 0) {
                runBatch(baselineFinder, heuristic, start, end, baselinePath, allocationBean);
                runBatch(candidateFinder, heuristic, start, end, candidatePath, allocationBean);
            } else {
                runBatch(candidateFinder, heuristic, start, end, candidatePath, allocationBean);
                runBatch(baselineFinder, heuristic, start, end, baselinePath, allocationBean);
            }
        }

        long[] baselineNanos = new long[MEASURED_BATCHES];
        long[] candidateNanos = new long[MEASURED_BATCHES];
        long[] baselineAllocated = new long[MEASURED_BATCHES];
        long[] candidateAllocated = new long[MEASURED_BATCHES];
        boolean found = false;
        int pathLength = 0;
        long baselineChecksum = 0;
        long candidateChecksum = 0;
        for (int batch = 0; batch < MEASURED_BATCHES; batch++) {
            BatchResult baseline;
            BatchResult candidate;
            if ((batch & 1) == 0) {
                baseline = runBatch(baselineFinder, heuristic, start, end, baselinePath, allocationBean);
                candidate = runBatch(candidateFinder, heuristic, start, end, candidatePath, allocationBean);
            } else {
                candidate = runBatch(candidateFinder, heuristic, start, end, candidatePath, allocationBean);
                baseline = runBatch(baselineFinder, heuristic, start, end, baselinePath, allocationBean);
            }
            if (baseline.found() != candidate.found() || baseline.pathLength() != candidate.pathLength()
                    || baseline.checksum() != candidate.checksum()) {
                throw new IllegalStateException("Path mismatch in " + scenario.name());
            }
            found = baseline.found();
            pathLength = baseline.pathLength();
            baselineChecksum = baseline.checksum();
            candidateChecksum = candidate.checksum();
            baselineNanos[batch] = baseline.elapsedNanos();
            candidateNanos[batch] = candidate.elapsedNanos();
            baselineAllocated[batch] = baseline.allocatedBytes();
            candidateAllocated[batch] = candidate.allocatedBytes();
            checksumSink ^= baselineChecksum ^ candidateChecksum;
        }

        double baselineMs = median(baselineNanos) / 1_000_000d / PATHS_PER_BATCH;
        double candidateMs = median(candidateNanos) / 1_000_000d / PATHS_PER_BATCH;
        double baselineKiB = median(baselineAllocated) / 1024d / PATHS_PER_BATCH;
        double candidateKiB = median(candidateAllocated) / 1024d / PATHS_PER_BATCH;
        System.out.printf(Locale.ROOT,
                "scenario=%s map=%dx%d pathsPerBatch=%d found=%s pathLength=%d checksumEqual=%s "
                        + "medianSearchMs=%.4f->%.4f deltaPct=%.1f medianAllocatedKiB=%.2f->%.2f deltaPct=%.1f%n",
                scenario.name(), WIDTH, HEIGHT, PATHS_PER_BATCH, found, pathLength,
                baselineChecksum == candidateChecksum, baselineMs, candidateMs,
                100d * (candidateMs - baselineMs) / baselineMs, baselineKiB, candidateKiB,
                100d * (candidateKiB - baselineKiB) / baselineKiB);
    }

    private static BatchResult runBatch(IndexedAStarPathFinder<IMapTileInformation> pathFinder,
            MapDistance heuristic, IMapTileInformation start, IMapTileInformation end,
            GraphPath<IMapTileInformation> path, com.sun.management.ThreadMXBean allocationBean) {
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = allocationBean == null ? -1 : allocationBean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        boolean found = false;
        long checksum = 0;
        path.clear();
        for (int i = 0; i < PATHS_PER_BATCH; i++) {
            path.clear();
            found = pathFinder.searchNodePath(start, end, heuristic, path);
            checksum = pathChecksum(path);
        }
        long elapsed = System.nanoTime() - started;
        long allocated = allocationBean == null
                ? -1 : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        return new BatchResult(elapsed, allocated, found, path.getCount(), checksum);
    }

    private record BatchResult(long elapsedNanos, long allocatedBytes, boolean found, int pathLength, long checksum) {
    }

    private static long pathChecksum(GraphPath<IMapTileInformation> path) {
        long checksum = 0xcbf29ce484222325L;
        for (IMapTileInformation tile : path) {
            checksum = (checksum ^ tile.getIndex()) * 0x100000001b3L;
        }
        return checksum;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean allocationBean
                && allocationBean.isThreadAllocatedMemorySupported()) {
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
            return allocationBean;
        }
        return null;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static Scenario[] scenarios() {
        boolean[][] open = new boolean[WIDTH][HEIGHT];
        boolean[][] detour = new boolean[WIDTH][HEIGHT];
        for (int wallX = 8, wallIndex = 0; wallX < WIDTH - 2; wallX += 8, wallIndex++) {
            int openingY = (wallIndex & 1) == 0 ? 10 : HEIGHT - 11;
            for (int y = 1; y < HEIGHT - 1; y++) {
                if (y != openingY) {
                    detour[wallX][y] = true;
                }
            }
        }
        boolean[][] unreachable = new boolean[WIDTH][HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            unreachable[WIDTH / 2][y] = true;
        }
        return new Scenario[]{
            new Scenario("open-long-route", open, 1, 1, WIDTH - 2, HEIGHT - 2),
            new Scenario("alternating-wall-detour", detour, 1, HEIGHT / 2, WIDTH - 2, HEIGHT / 2),
            new Scenario("unreachable-across-wall", unreachable, 1, HEIGHT / 2, WIDTH - 2, HEIGHT / 2)
        };
    }

    private record Scenario(String name, boolean[][] solid, int startX, int startY, int endX, int endY) {
    }

    /** The pre-change graph expansion, retained here for same-process A/B runs. */
    private static final class BaselineGraph implements IndexedGraph<IMapTileInformation> {

        private final IMapController mapController;
        private final IEntityPositionLookup entityPositionLookup;
        private final int nodeCount;
        private INavigable pathFindable;

        private BaselineGraph(IMapController mapController, IEntityPositionLookup entityPositionLookup) {
            this.mapController = mapController;
            this.entityPositionLookup = entityPositionLookup;
            nodeCount = mapController.getMapData().getHeight() * mapController.getMapData().getWidth();
        }

        private void setPathFindable(INavigable pathFindable) {
            this.pathFindable = pathFindable;
        }

        @Override
        public int getNodeCount() {
            return nodeCount;
        }

        @Override
        public int getIndex(IMapTileInformation tile) {
            return tile.getIndex();
        }

        @Override
        public Array<Connection<IMapTileInformation>> getConnections(IMapTileInformation tile) {
            Array<Connection<IMapTileInformation>> connections = new Array<>(pathFindable.canMoveDiagonally() ? 8 : 4);
            boolean[] valid = new boolean[4];
            valid[0] = addIfValidCoordinate(tile, tile.getX(), tile.getY() - 1, connections);
            valid[1] = addIfValidCoordinate(tile, tile.getX() + 1, tile.getY(), connections);
            valid[2] = addIfValidCoordinate(tile, tile.getX(), tile.getY() + 1, connections);
            valid[3] = addIfValidCoordinate(tile, tile.getX() - 1, tile.getY(), connections);
            if (pathFindable.canMoveDiagonally()) {
                if (valid[0] && valid[1]) {
                    addIfValidCoordinate(tile, tile.getX() + 1, tile.getY() - 1, connections);
                }
                if (valid[0] && valid[3]) {
                    addIfValidCoordinate(tile, tile.getX() - 1, tile.getY() - 1, connections);
                }
                if (valid[2] && valid[1]) {
                    addIfValidCoordinate(tile, tile.getX() + 1, tile.getY() + 1, connections);
                }
                if (valid[2] && valid[3]) {
                    addIfValidCoordinate(tile, tile.getX() - 1, tile.getY() + 1, connections);
                }
            }
            return connections;
        }

        private boolean addIfValidCoordinate(IMapTileInformation from, int x, int y,
                Array<Connection<IMapTileInformation>> connections) {
            IMapTileInformation to = mapController.getMapData().getTile(x, y);
            if (to == null) {
                return false;
            }
            Float cost = pathFindable.getCost(from, to, mapController, entityPositionLookup);
            if (cost == null) {
                return false;
            }
            connections.add(new DefaultConnection<IMapTileInformation>(from, to) {
                @Override
                public float getCost() {
                    return cost;
                }
            });
            return true;
        }
    }

    static final class Fixture {

        private static final Terrain FLOOR = TerrainFixtures.terrain((short) 1,
                EnumSet.noneOf(Terrain.TerrainFlag.class), 0, 0, 0, (short) 1);
        private static final Terrain ROCK = TerrainFixtures.terrain((short) 2,
                TerrainFixtures.flags(Terrain.TerrainFlag.SOLID), 0, 0, 0, (short) 2);

        final GridMapData mapData;
        private final IMapController mapController;
        private final IEntityPositionLookup entityPositions;

        Fixture(boolean[][] solid) {
            mapData = new GridMapData(solid);
            mapController = (IMapController) Proxy.newProxyInstance(IMapController.class.getClassLoader(),
                    new Class<?>[]{IMapController.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getMapData" -> mapData;
                        case "getTerrain" -> ((IMapTileInformation) args[0]).getTerrainId() == 2 ? ROCK : FLOOR;
                        case "getRoomControllerByCoordinates" -> null;
                        case "toString" -> "BenchmarkMapController";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            entityPositions = (IEntityPositionLookup) Proxy.newProxyInstance(
                    IEntityPositionLookup.class.getClassLoader(), new Class<?>[]{IEntityPositionLookup.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isTileBlocked" -> false;
                        case "toString" -> "BenchmarkEntityPositionLookup";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        IMapController mapController() {
            return mapController;
        }

        IEntityPositionLookup entityPositions() {
            return entityPositions;
        }
    }

    static final class GridMapData implements IMapDataInformation<IMapTileController> {

        private final int width;
        private final int height;
        private final IMapTileController[][] tiles;

        private GridMapData(boolean[][] solid) {
            width = solid.length;
            height = solid[0].length;
            tiles = new IMapTileController[width][height];
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    tiles[x][y] = new BenchmarkTile(x, y, solid[x][y] ? (short) 2 : (short) 1);
                }
            }
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public IMapTileController getTile(int x, int y) {
            return x < 0 || y < 0 || x >= width || y >= height ? null : tiles[x][y];
        }

        @Override
        public void setTiles(java.util.List<IMapTileController> mapTiles) {
            for (IMapTileController tile : mapTiles) {
                tiles[tile.getX()][tile.getY()] = tile;
            }
        }
    }

    private static final class BenchmarkTile extends AbstractMapTileInformation implements IMapTileController {

        private final int index;
        private Point location;
        private short terrainId;

        private BenchmarkTile(int x, int y, short terrainId) {
            super(new EntityId((long) y * WIDTH + x));
            index = y * WIDTH + x;
            location = new Point(x, y);
            this.terrainId = terrainId;
        }

        @Override
        protected <T extends EntityComponent> T getEntityComponent(Class<T> type) {
            throw new UnsupportedOperationException(type.getName());
        }

        @Override
        public EntityId getEntityId() {
            return entityId;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public Point getLocation() {
            return location;
        }

        @Override
        public int getX() {
            return location.x;
        }

        @Override
        public int getY() {
            return location.y;
        }

        @Override
        public short getTerrainId() {
            return terrainId;
        }

        @Override
        public EntityId getRoomId() {
            return null;
        }

        @Override
        public short getOwnerId() {
            return OWNER_ID;
        }

        @Override
        public int getGold() {
            return 0;
        }

        @Override
        public int getHealth() {
            return 0;
        }

        @Override
        public Integer getHealthPercent() {
            return 100;
        }

        @Override
        public int getManaGain() {
            return 0;
        }

        @Override
        public int getMaxHealth() {
            return 0;
        }

        @Override
        public int getRandomTextureIndex() {
            return 0;
        }

        @Override
        public toniarts.openkeeper.tools.convert.map.Tile.BridgeTerrainType getBridgeTerrainType() {
            return null;
        }

        @Override
        public boolean isAtFullHealth() {
            return true;
        }

        @Override
        public boolean isFlashed(short playerId) {
            return false;
        }

        @Override
        public boolean isSelected(short playerId) {
            return false;
        }

        @Override
        public void setBridgeTerrainType(toniarts.openkeeper.tools.convert.map.Tile.BridgeTerrainType bridgeTerrainType) {
        }

        @Override
        public void setFlashed(boolean flashed, short playerId) {
        }

        @Override
        public void setGold(int gold) {
        }

        @Override
        public void setHealth(int health) {
        }

        @Override
        public void setIndex(int index) {
        }

        @Override
        public void setMaxHealth(int maxHealth) {
        }

        @Override
        public void setOwnerId(short ownerId) {
        }

        @Override
        public void setPoint(Point point) {
            location = point;
        }

        @Override
        public void setRandomTextureIndex(int randomTextureIndex) {
        }

        @Override
        public void setSelected(boolean selected, short playerId) {
        }

        @Override
        public void setTerrainId(short terrainId) {
            this.terrainId = terrainId;
        }

        @Override
        public void setRoomId(EntityId entityId) {
        }
    }

    static final class BenchmarkNavigable implements INavigable {

        @Override
        public short getOwnerId() {
            return OWNER_ID;
        }

        @Override
        public boolean canFly() {
            return false;
        }

        @Override
        public boolean canWalkOnWater() {
            return false;
        }

        @Override
        public boolean canWalkOnLava() {
            return false;
        }

        @Override
        public boolean canMoveDiagonally() {
            return false;
        }
    }
}
