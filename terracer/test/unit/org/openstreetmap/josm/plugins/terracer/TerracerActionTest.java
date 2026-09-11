// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.terracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link TerracerAction}
 */
@Main
@Projection
class TerracerActionTest {
    private DataSet ds;

    @BeforeEach
    void setUp() {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "TerracerActionTest", null));
    }

    private Node node(double lat, double lon) {
        Node n = new Node(new LatLon(lat, lon));
        ds.addPrimitive(n);
        return n;
    }

    private Way closedWay(Node... nodes) {
        Way w = new Way();
        w.setNodes(Arrays.asList(nodes));
        w.addNode(nodes[0]);
        ds.addPrimitive(w);
        return w;
    }

    private List<Way> buildings() {
        return ds.getWays().stream().filter(w -> w.hasKey("building") && !w.isDeleted())
                .sorted((a, b) -> Double.compare(centerLon(a), centerLon(b))).collect(Collectors.toList());
    }

    private static double centerLon(Way w) {
        return w.getNodes().stream().mapToDouble(Node::lon).average().orElse(0);
    }

    private static void assertValidOutline(Way w) {
        List<Node> nodes = w.getNodes();
        assertTrue(w.isClosed(), "not closed: " + nodes);
        assertEquals(nodes.size() - 1, new HashSet<>(nodes).size(), "duplicate node in " + nodes);
    }

    /**
     * Entrances on the front and a footway junction on the back of a long building survive
     * terracing and end up on the wall of the right house.
     */
    @Test
    void testFeatureNodesAreKept() {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node c = node(50.0002, 10.0012);
        Node d = node(50.0000, 10.0012);
        Node entrance1 = node(50.0000, 10.0002);
        entrance1.put("entrance", "yes");
        Node entrance2 = node(50.0000, 10.0006);
        entrance2.put("entrance", "yes");
        Node entrance3 = node(50.0000, 10.0011);
        entrance3.put("entrance", "yes");
        Node junction = node(50.0002, 10.0005);
        Way footway = new Way();
        footway.setNodes(Arrays.asList(node(50.0004, 10.0005), junction));
        footway.put("highway", "footway");
        ds.addPrimitive(footway);
        Way outline = closedWay(a, b, junction, c, d, entrance3, entrance2, entrance1);
        outline.put("building", "yes");

        new TerracerAction().terraceBuilding(outline, null, null, null, 3,
                null, null, 1, Collections.emptyList(), null, false, false, "house", false);

        List<Way> houses = buildings();
        assertEquals(3, houses.size());
        houses.forEach(TerracerActionTest::assertValidOutline);
        // house 1: lon 10.0000 .. 10.0004, house 2: .. 10.0008, house 3: .. 10.0012
        assertTrue(houses.get(0).containsNode(entrance1), "entrance 1 in first house");
        assertTrue(houses.get(1).containsNode(entrance2), "entrance 2 in second house");
        assertTrue(houses.get(1).containsNode(junction), "footway junction in second house");
        assertTrue(houses.get(2).containsNode(entrance3), "entrance 3 in third house");
        assertEquals(6, houses.get(0).getNodesCount());
        assertEquals(7, houses.get(1).getNodesCount());
        assertEquals(6, houses.get(2).getNodesCount());
        // entrance 2 sits on the front between the two wall nodes of the second house
        List<Node> front = new ArrayList<>(houses.get(1).getNodes());
        front.removeIf(n -> n.lat() > 50.0001);
        int idx = front.indexOf(entrance2);
        assertTrue(idx > 0 && idx < front.size() - 1, "entrance between front corners: " + front);
        assertTrue(footway.containsNode(junction));
        assertFalse(entrance1.isDeleted());
        assertFalse(junction.isDeleted());
        // all positions unchanged
        assertEquals(10.0002, entrance1.lon(), 1e-9);
        assertEquals(10.0005, junction.lon(), 1e-9);
    }

    /**
     * An entrance on the short side of the building must not be mistaken for a corner:
     * the building is still split along its long side.
     */
    @Test
    void testEntranceOnShortSide() {
        Node a = node(50.0000, 10.0000);
        Node sideEntrance = node(50.0001, 10.0000);
        sideEntrance.put("entrance", "yes");
        Node b = node(50.0002, 10.0000);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way outline = closedWay(a, sideEntrance, b, c, d);
        outline.put("building", "yes");

        new TerracerAction().terraceBuilding(outline, null, null, null, 2,
                null, null, 1, Collections.emptyList(), null, false, false, "house", false);

        List<Way> houses = buildings();
        assertEquals(2, houses.size());
        houses.forEach(TerracerActionTest::assertValidOutline);
        // split in the middle of the long side
        for (Way h : houses) {
            assertTrue(h.getNodes().stream().anyMatch(n -> Math.abs(n.lon() - 10.0005) < 1e-9), "wall at lon 10.0005: " + h.getNodes());
        }
        assertTrue(houses.get(0).containsNode(sideEntrance));
        assertEquals(6, houses.get(0).getNodesCount());
        assertEquals(5, houses.get(1).getNodesCount());
    }

    /**
     * An entrance exactly where a new wall is created becomes the wall node instead of
     * a duplicate.
     */
    @Test
    void testEntranceAtNewWall() {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Node entrance = node(50.0000, 10.0005);
        entrance.put("entrance", "yes");
        Way outline = closedWay(a, b, c, d, entrance);
        outline.put("building", "yes");

        new TerracerAction().terraceBuilding(outline, null, null, null, 2,
                null, null, 1, Collections.emptyList(), null, false, false, "house", false);

        List<Way> houses = buildings();
        assertEquals(2, houses.size());
        houses.forEach(TerracerActionTest::assertValidOutline);
        assertTrue(houses.get(0).containsNode(entrance));
        assertTrue(houses.get(1).containsNode(entrance));
        assertEquals(5, houses.get(0).getNodesCount());
        assertEquals(5, houses.get(1).getNodesCount());
    }

    /**
     * Without feature nodes the result is the same as before.
     */
    @Test
    void testPlainOutline() {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way outline = closedWay(a, b, c, d);
        outline.put("building", "yes");

        new TerracerAction().terraceBuilding(outline, null, null, null, 2,
                null, null, 1, Collections.emptyList(), null, false, false, "house", false);

        List<Way> houses = buildings();
        assertEquals(2, houses.size());
        houses.forEach(TerracerActionTest::assertValidOutline);
        assertEquals(5, houses.get(0).getNodesCount());
        assertEquals(5, houses.get(1).getNodesCount());
    }
}
