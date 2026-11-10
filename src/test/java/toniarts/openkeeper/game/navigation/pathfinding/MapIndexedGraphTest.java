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
import com.badlogic.gdx.ai.pfa.DefaultGraphPath;
import com.badlogic.gdx.ai.pfa.GraphPath;
import com.badlogic.gdx.utils.Array;
import org.junit.jupiter.api.Test;
import toniarts.openkeeper.game.map.IMapTileController;
import toniarts.openkeeper.game.map.IMapTileInformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapIndexedGraphTest {

    private static final int MAP_SIZE = 85;

    @Test
    void reusesSourceConnectionsWithoutChangingOtherTilesAndRefreshesTerrainChanges() {
        boolean[][] solid = new boolean[MAP_SIZE][MAP_SIZE];
        MapIndexedGraphBenchmark.Fixture fixture = new MapIndexedGraphBenchmark.Fixture(solid);
        MapIndexedGraph graph = new MapIndexedGraph(fixture.mapController(), fixture.entityPositions());
        graph.setPathFindable(new MapIndexedGraphBenchmark.BenchmarkNavigable());

        IMapTileController start = fixture.mapData.getTile(1, 1);
        Array<Connection<IMapTileInformation>> startConnections = graph.getConnections(start);
        Connection<IMapTileInformation> northEdge = startConnections.get(0);
        Array<Connection<IMapTileInformation>> otherConnections = graph.getConnections(fixture.mapData.getTile(2, 2));

        assertNotSame(startConnections, otherConnections);
        assertSame(startConnections, graph.getConnections(start));
        assertSame(northEdge, startConnections.get(0));
        assertSame(start, northEdge.getFromNode());
        assertSame(fixture.mapData.getTile(1, 0), northEdge.getToNode());

        MapPathFinder pathFinder = new MapPathFinder(graph, false);
        GraphPath<IMapTileInformation> path = new DefaultGraphPath<>();
        IMapTileInformation end = fixture.mapData.getTile(3, 1);

        assertTrue(pathFinder.searchNodePath(start, end, new MapDistance(), path));
        assertEquals(3, path.getCount());
        assertEquals(1 * MAP_SIZE + 1, path.get(0).getIndex());
        assertEquals(1 * MAP_SIZE + 3, path.get(path.getCount() - 1).getIndex());

        fixture.mapData.getTile(2, 1).setTerrainId((short) 2);
        path.clear();
        assertTrue(pathFinder.searchNodePath(start, end, new MapDistance(), path));
        assertEquals(5, path.getCount());
        for (int i = 0; i < path.getCount(); i++) {
            assertFalse(path.get(i).getIndex() == 1 * MAP_SIZE + 2);
        }

        fixture.mapData.getTile(2, 1).setTerrainId((short) 1);
        path.clear();
        assertTrue(pathFinder.searchNodePath(start, end, new MapDistance(), path));
        assertEquals(3, path.getCount());
    }
}
