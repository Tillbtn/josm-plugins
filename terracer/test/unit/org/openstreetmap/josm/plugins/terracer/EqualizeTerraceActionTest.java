// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.terracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.testutils.annotations.Projection;

/**
 * Test class for {@link EqualizeTerraceAction}
 */
@Projection
class EqualizeTerraceActionTest {
    private static final double DELTA = 1e-7;

    private final DataSet ds = new DataSet();

    private Node node(double lat, double lon) {
        Node n = new Node(new LatLon(lat, lon));
        ds.addPrimitive(n);
        return n;
    }

    private Way building(Node... nodes) {
        Way w = new Way();
        w.setNodes(Arrays.asList(nodes));
        w.addNode(nodes[0]);
        w.put("building", "house");
        ds.addPrimitive(w);
        return w;
    }

    private static void assertPosition(Node n, double lat, double lon) {
        assertEquals(lat, n.lat(), DELTA, "latitude of " + n);
        assertEquals(lon, n.lon(), DELTA, "longitude of " + n);
    }

    /**
     * Two semi-detached houses of different width: the shared wall moves to the middle.
     */
    @Test
    void testSemiDetached() throws Exception {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node wallBack = node(50.0002, 10.0003);
        Node wallFront = node(50.0000, 10.0003);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way left = building(a, b, wallBack, wallFront);
        Way right = building(wallFront, wallBack, c, d);

        Command cmd = EqualizeTerraceAction.equalize(Arrays.asList(left, right));
        assertNotNull(cmd);
        assertTrue(cmd.executeCommand());

        assertPosition(wallFront, 50.0000, 10.0005);
        assertPosition(wallBack, 50.0002, 10.0005);
        // corners untouched
        assertPosition(a, 50.0000, 10.0000);
        assertPosition(c, 50.0002, 10.0010);
        // ways and tags untouched
        assertEquals(5, left.getNodesCount());
        assertEquals(5, right.getNodesCount());
        assertEquals("house", left.get("building"));

        cmd.undoCommand();
        assertPosition(wallFront, 50.0000, 10.0003);
    }

    /**
     * The shared wall may have an intermediate node, which keeps its relative position on the wall.
     * The outer wall of one house may be kinked at the wall node; it gets straightened.
     */
    @Test
    void testWallWithIntermediateNodeAndKink() throws Exception {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node wallBack = node(50.00021, 10.0003);
        Node wallMiddle = node(50.0001, 10.0003);
        Node wallFront = node(50.0000, 10.0003);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way left = building(a, b, wallBack, wallMiddle, wallFront);
        Way right = building(wallFront, wallMiddle, wallBack, c, d);

        Command cmd = EqualizeTerraceAction.equalize(Arrays.asList(left, right));
        assertNotNull(cmd);
        assertTrue(cmd.executeCommand());

        assertPosition(wallFront, 50.0000, 10.0005);
        assertPosition(wallBack, 50.0002, 10.0005);
        // the intermediate node was at 0.0001/0.00021 of the old wall and keeps that fraction on the new wall
        assertPosition(wallMiddle, 50.0002 * 0.0001 / 0.00021 + 50.0000 * (1 - 0.0001 / 0.00021), 10.0005);
    }

    /**
     * A row of three houses: walls move to one third and two thirds, regardless of selection order.
     */
    @Test
    void testRowOfThree() throws Exception {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node w1Back = node(50.0002, 10.0002);
        Node w1Front = node(50.0000, 10.0002);
        Node w2Back = node(50.0002, 10.0009);
        Node w2Front = node(50.0000, 10.0009);
        Node c = node(50.0002, 10.0012);
        Node d = node(50.0000, 10.0012);
        Way h1 = building(a, b, w1Back, w1Front);
        Way h2 = building(w1Front, w1Back, w2Back, w2Front);
        Way h3 = building(w2Front, w2Back, c, d);

        Command cmd = EqualizeTerraceAction.equalize(Arrays.asList(h3, h1, h2));
        assertNotNull(cmd);
        assertTrue(cmd.executeCommand());

        assertPosition(w1Front, 50.0000, 10.0004);
        assertPosition(w1Back, 50.0002, 10.0004);
        assertPosition(w2Front, 50.0000, 10.0008);
        assertPosition(w2Back, 50.0002, 10.0008);
    }

    /**
     * Row along the short side of the block: the walls still move along the outline.
     */
    @Test
    void testRowAlongShortSide() throws Exception {
        // block is 10 m wide (lon) and 2 m high (lat), houses stacked in lat direction
        Node a = node(50.0000, 10.0000);
        Node wallLeft = node(50.00005, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node c = node(50.0002, 10.0010);
        Node wallRight = node(50.00005, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way lower = building(a, wallLeft, wallRight, d);
        Way upper = building(wallLeft, b, c, wallRight);

        Command cmd = EqualizeTerraceAction.equalize(Arrays.asList(lower, upper));
        assertNotNull(cmd);
        assertTrue(cmd.executeCommand());

        assertPosition(wallLeft, 50.0001, 10.0000);
        assertPosition(wallRight, 50.0001, 10.0010);
    }

    /**
     * Entrance nodes on the front of both houses are no corners: the wall still moves to the
     * middle and the entrances keep their position and their house.
     */
    @Test
    void testEntrancesOnFront() throws Exception {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node wallBack = node(50.0002, 10.0003);
        Node wallFront = node(50.0000, 10.0003);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Node entranceLeft = node(50.0000, 10.00015);
        entranceLeft.put("entrance", "yes");
        Node entranceRight = node(50.0000, 10.0008);
        entranceRight.put("entrance", "yes");
        // a footway ending at the right entrance, not selected
        Way footway = new Way();
        footway.setNodes(Arrays.asList(node(49.9998, 10.0008), entranceRight));
        footway.put("highway", "footway");
        ds.addPrimitive(footway);
        Way left = building(a, b, wallBack, wallFront, entranceLeft);
        Way right = building(wallFront, wallBack, c, d, entranceRight);

        Command cmd = EqualizeTerraceAction.equalize(Arrays.asList(left, right));
        assertNotNull(cmd);
        assertTrue(cmd.executeCommand());

        assertPosition(wallFront, 50.0000, 10.0005);
        assertPosition(wallBack, 50.0002, 10.0005);
        assertPosition(entranceLeft, 50.0000, 10.00015);
        assertPosition(entranceRight, 50.0000, 10.0008);
        assertEquals(6, left.getNodesCount());
        assertEquals(6, right.getNodesCount());
        assertTrue(left.containsNode(entranceLeft));
        assertTrue(right.containsNode(entranceRight));
        assertTrue(footway.containsNode(entranceRight));
        assertValidOutline(left);
        assertValidOutline(right);
    }

    /**
     * If the wall moves past an entrance, the entrance changes to the neighbouring house.
     */
    @Test
    void testEntrancePassedByWall() throws Exception {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node wallBack = node(50.0002, 10.0003);
        Node wallFront = node(50.0000, 10.0003);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Node entrance = node(50.0000, 10.0004);
        entrance.put("entrance", "yes");
        Node backEntrance = node(50.0002, 10.00045);
        backEntrance.put("entrance", "service");
        Way left = building(a, b, wallBack, wallFront);
        Way right = building(wallFront, wallBack, backEntrance, c, d, entrance);

        Command cmd = EqualizeTerraceAction.equalize(Arrays.asList(left, right));
        assertNotNull(cmd);
        assertTrue(cmd.executeCommand());

        assertPosition(wallFront, 50.0000, 10.0005);
        assertPosition(entrance, 50.0000, 10.0004);
        assertTrue(left.containsNode(entrance), "entrance moved to the left house");
        assertTrue(left.containsNode(backEntrance), "back entrance moved to the left house");
        assertEquals(7, left.getNodesCount());
        assertEquals(5, right.getNodesCount());
        assertValidOutline(left);
        assertValidOutline(right);
        // the orientation of the ways is kept
        assertEquals(a, left.getNode(0));

        cmd.undoCommand();
        assertEquals(5, left.getNodesCount());
        assertTrue(right.containsNode(entrance));
        assertPosition(wallFront, 50.0000, 10.0003);
    }

    /**
     * An entrance on the end wall of a house stays where it is and is not mistaken for a corner.
     */
    @Test
    void testEntranceOnEndWall() throws Exception {
        Node a = node(50.0000, 10.0000);
        Node endEntrance = node(50.0001, 10.0000);
        endEntrance.put("entrance", "yes");
        Node b = node(50.0002, 10.0000);
        Node wallBack = node(50.0002, 10.0003);
        Node wallFront = node(50.0000, 10.0003);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way left = building(a, endEntrance, b, wallBack, wallFront);
        Way right = building(wallFront, wallBack, c, d);

        Command cmd = EqualizeTerraceAction.equalize(Arrays.asList(left, right));
        assertNotNull(cmd);
        assertTrue(cmd.executeCommand());

        assertPosition(wallFront, 50.0000, 10.0005);
        assertPosition(wallBack, 50.0002, 10.0005);
        assertPosition(endEntrance, 50.0001, 10.0000);
        assertEquals(6, left.getNodesCount());
        assertValidOutline(left);
    }

    /**
     * Checks that consecutive nodes of the outline are distinct and that no node occurs twice
     * (apart from the closing node).
     */
    private static void assertValidOutline(Way w) {
        java.util.List<Node> nodes = w.getNodes();
        assertEquals(nodes.get(0), nodes.get(nodes.size() - 1));
        assertEquals(nodes.size() - 1, new java.util.HashSet<>(nodes).size(), "duplicate node in " + nodes);
        // the outline must not self-intersect: for these axis-aligned tests the area must be positive
        double area = 0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            area += nodes.get(i).lon() * nodes.get(i + 1).lat() - nodes.get(i + 1).lon() * nodes.get(i).lat();
        }
        assertTrue(Math.abs(area) > 1e-9, "degenerate outline " + nodes);
    }

    @Test
    void testAlreadyEqual() throws Exception {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node wallBack = node(50.0002, 10.0005);
        Node wallFront = node(50.0000, 10.0005);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way left = building(a, b, wallBack, wallFront);
        Way right = building(wallFront, wallBack, c, d);

        assertNull(EqualizeTerraceAction.equalize(Arrays.asList(left, right)));
    }

    @Test
    void testInvalidSelections() {
        Node a = node(50.0000, 10.0000);
        Node b = node(50.0002, 10.0000);
        Node wallBack = node(50.0002, 10.0003);
        Node wallFront = node(50.0000, 10.0003);
        Node c = node(50.0002, 10.0010);
        Node d = node(50.0000, 10.0010);
        Way left = building(a, b, wallBack, wallFront);
        Way right = building(wallFront, wallBack, c, d);
        Way detached = building(node(50.0005, 10.0000), node(50.0007, 10.0000), node(50.0007, 10.0003), node(50.0005, 10.0003));
        Way corner = building(c, node(50.0004, 10.0010), node(50.0004, 10.0013), node(50.0002, 10.0013));

        assertThrows(EqualizeTerraceAction.InvalidSelectionException.class,
                () -> EqualizeTerraceAction.equalize(Arrays.asList(left)));
        assertThrows(EqualizeTerraceAction.InvalidSelectionException.class,
                () -> EqualizeTerraceAction.equalize(Arrays.asList(left, detached)));
        assertThrows(EqualizeTerraceAction.InvalidSelectionException.class,
                () -> EqualizeTerraceAction.equalize(Arrays.asList(left, right, detached)));
        assertThrows(EqualizeTerraceAction.InvalidSelectionException.class,
                () -> EqualizeTerraceAction.equalize(Arrays.asList(left, right, corner)));
    }
}
