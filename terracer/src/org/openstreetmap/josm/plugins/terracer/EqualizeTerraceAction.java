// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.terracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Pair;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Tool to make the buildings of an existing terrace (or a pair of semi-detached
 * houses) equally wide.
 * <p>
 * Select two or more closed ways that form a single row, i.e. each building shares
 * a wall (an edge of two nodes) with the next one. The shared walls are then moved so
 * that the front and the back of the whole row are divided into equally long parts,
 * just like {@link TerracerAction} would have done when terracing the combined outline.
 * <p>
 * In contrast to terracing a fresh outline only nodes are moved. The ways, their tags,
 * their relation memberships and their history are kept, so there is no need to redraw
 * the buildings or to replace their geometry afterwards.
 * <p>
 * The front and the back of the row are assumed to be straight lines between the corners
 * of the two end buildings (see {@link TerracerAction#CORNER_ANGLE_DEGREES}). Additional
 * nodes on the front and back, e.g. entrances or the junctions of footways, keep their
 * position. If a moved wall passes such a node, the node is transferred to the neighbouring
 * building so that both outlines stay valid.
 */
public class EqualizeTerraceAction extends JosmAction {

    /**
     * Thrown when the selection cannot be equalized. The message is translated
     * and can be shown to the user.
     */
    static class InvalidSelectionException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidSelectionException(String message) {
            super(message);
        }
    }

    /**
     * A wall shared by two adjacent buildings of the row.
     */
    private static final class Wall {
        /** the wall node lying on the front of the row */
        Node front;
        /** the wall node lying on the back of the row */
        Node back;
        /** wall nodes not lying on the outline of the row, ordered from front to back */
        final List<Node> interior = new ArrayList<>();
        /** all nodes of the wall lying on the outline of the row */
        final List<Node> ends = new ArrayList<>();
    }

    /**
     * A node on the front or back of the row that is neither a corner nor a wall node,
     * e.g. an entrance. It keeps its position and is assigned to the building it lies in.
     */
    private static final class Rider {
        final Node node;
        /** position along the front or back, 0 = first building, 1 = last building */
        final double t;

        Rider(Node node, double t) {
            this.node = node;
            this.t = t;
        }
    }

    /**
     * Create a new action for equalizing a terrace
     */
    public EqualizeTerraceAction() {
        super(tr("Equalize a terrace"),
            "equalize_terrace",
            tr("Moves the shared walls of adjacent buildings so that all buildings become equally wide."),
            Shortcut.registerShortcut("tools:EqualizeTerrace",
                    tr("More tools: {0}", tr("Equalize a terrace")),
                    KeyEvent.VK_E, Shortcut.ALT_CTRL_SHIFT),
                        true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null)
            return;
        try {
            Command cmd = equalize(ds.getSelectedWays());
            if (cmd == null) {
                new ExtendedDialog(MainApplication.getMainFrame(), tr("Nothing to do"), "OK")
                    .setButtonIcons("ok").setIcon(JOptionPane.INFORMATION_MESSAGE)
                    .setContent(tr("The selected buildings are already equally wide."))
                    .showDialog();
                return;
            }
            UndoRedoHandler.getInstance().add(cmd);
        } catch (InvalidSelectionException ex) {
            Logging.warn("Terracer: " + ex.getMessage());
            new ExtendedDialog(MainApplication.getMainFrame(), tr("Invalid selection"), "OK")
                .setButtonIcons("ok").setIcon(JOptionPane.INFORMATION_MESSAGE)
                .setContent(ex.getMessage() + "\n\n" + tr("Select two or more closed ways forming a single row of buildings, "
                        + "where each building shares a wall with the next one."))
                .showDialog();
        }
    }

    /**
     * Computes the command that moves the shared walls of the given buildings so that all
     * buildings become equally wide.
     *
     * @param selection the selected ways, must form a single row of buildings
     * @return the command, or {@code null} if all nodes are already in place
     * @throws InvalidSelectionException if the selection is not a single row of buildings
     */
    static Command equalize(Collection<Way> selection) throws InvalidSelectionException {
        final List<Way> ways = new ArrayList<>(selection);
        final int n = ways.size();
        if (n < 2)
            throw new InvalidSelectionException(tr("Select at least two buildings."));
        for (Way w : ways) {
            if (!w.isClosed() || w.getNodesCount() < 4)
                throw new InvalidSelectionException(tr("{0} is not a closed way.", name(w)));
        }

        final List<Way> order = orderRow(ways);
        final Way first = order.get(0);
        final Way last = order.get(n - 1);

        final List<Node> ring = buildRing(ways);
        final Set<Node> ringNodes = new HashSet<>(ring);

        // the walls between consecutive buildings of the row
        final List<Wall> walls = new ArrayList<>(n - 1);
        final Set<Node> wallNodes = new HashSet<>();
        for (int i = 0; i < n - 1; i++) {
            Wall wall = new Wall();
            Set<Node> shared = new LinkedHashSet<>(order.get(i).getNodes());
            shared.retainAll(order.get(i + 1).getNodes());
            for (Node node : shared) {
                if (ringNodes.contains(node)) {
                    wall.ends.add(node);
                } else {
                    wall.interior.add(node);
                }
            }
            if (wall.ends.size() != 2)
                throw new InvalidSelectionException(
                        tr("The wall shared by {0} and {1} must have exactly two nodes on the outline of the row.",
                        name(order.get(i)), name(order.get(i + 1))));
            walls.add(wall);
            wallNodes.addAll(wall.ends);
        }

        // Normalize the ring: it starts at a node of the first wall, followed by the arc
        // belonging to the first building (the "left end" of the row) up to the other node
        // of the first wall.
        final Wall wall0 = walls.get(0);
        Collections.rotate(ring, -ring.indexOf(wall0.ends.get(0)));
        int k = ring.indexOf(wall0.ends.get(1));
        if (!allBelongTo(ring, 1, k - 1, first)) {
            Collections.reverse(ring.subList(1, ring.size()));
            k = ring.indexOf(wall0.ends.get(1));
            if (!allBelongTo(ring, 1, k - 1, first))
                throw new InvalidSelectionException(tr("The outline of {0} is not a simple polygon.", name(first)));
        }

        // The arc of the last building (the "right end") lies between the two nodes of the last wall.
        final Wall wallLast = walls.get(n - 2);
        final int ic = Math.min(ring.indexOf(wallLast.ends.get(0)), ring.indexOf(wallLast.ends.get(1)));
        final int id = Math.max(ring.indexOf(wallLast.ends.get(0)), ring.indexOf(wallLast.ends.get(1)));
        final int lastArcStart;
        final int lastArcEnd;
        if (ic > k && allBelongTo(ring, ic + 1, id - 1, last)) {
            lastArcStart = ic + 1;
            lastArcEnd = id - 1;
        } else if (ic == 0 && id == k && allBelongTo(ring, k + 1, ring.size() - 1, last)) {
            lastArcStart = k + 1;
            lastArcEnd = ring.size() - 1;
        } else {
            throw new InvalidSelectionException(tr("The outline of {0} is not a simple polygon.", name(last)));
        }

        // The corners of the row: the first and last real corner in each end arc.
        // Nodes in between with a small change of direction (entrances etc.) are no corners.
        final int ia = firstCorner(ring, 1, k - 1, 1);
        final int ib = firstCorner(ring, 1, k - 1, -1);
        if (ia < 0)
            throw new InvalidSelectionException(tr("{0} has no corner of its own.", name(first)));
        final int ig = firstCorner(ring, lastArcStart, lastArcEnd, 1);
        final int iF = firstCorner(ring, lastArcStart, lastArcEnd, -1);
        if (ig < 0)
            throw new InvalidSelectionException(tr("{0} has no corner of its own.", name(last)));
        final Node cornerA = ring.get(ia); // front corner of the first building
        final Node cornerB = ring.get(ib); // back corner of the first building
        final Node cornerG = ring.get(ig); // back corner of the last building
        final Node cornerF = ring.get(iF); // front corner of the last building

        // Classify the wall nodes into front and back nodes and check that the walls
        // appear in the order of the row along the outline. In ring order the back runs
        // from the first to the last building, the front from the last to the first.
        int lastBack = ib;
        int lastFront = ring.size() + 1;
        for (Wall wall : walls) {
            for (Node node : wall.ends) {
                int idx = ring.indexOf(node);
                if (idx > ib && idx < ig) {
                    wall.back = node;
                    if (idx <= lastBack)
                        throw new InvalidSelectionException(tr("The buildings are not arranged in a row."));
                    lastBack = idx;
                } else if (idx == 0 || idx > iF) {
                    wall.front = node;
                    int pos = idx == 0 ? ring.size() : idx;
                    if (pos >= lastFront)
                        throw new InvalidSelectionException(tr("The buildings are not arranged in a row."));
                    lastFront = pos;
                }
            }
            if (wall.front == null || wall.back == null)
                throw new InvalidSelectionException(tr("The buildings are not arranged in a row."));
        }

        // Intermediate nodes on the front and on the back keep their position; remember
        // where they are along the row.
        final EastNorth enA = cornerA.getEastNorth();
        final EastNorth enB = cornerB.getEastNorth();
        final EastNorth enF = cornerF.getEastNorth();
        final EastNorth enG = cornerG.getEastNorth();
        final List<Rider> frontRiders = new ArrayList<>();
        final List<Rider> backRiders = new ArrayList<>();
        for (int i = iF + 1; i < ring.size() + ia; i++) {
            Node node = ring.get(i % ring.size());
            if (!wallNodes.contains(node))
                frontRiders.add(new Rider(node, clamp(projectionParameter(enA, enF, node.getEastNorth()))));
        }
        for (int i = ib + 1; i < ig; i++) {
            Node node = ring.get(i);
            if (!wallNodes.contains(node))
                backRiders.add(new Rider(node, clamp(projectionParameter(enB, enG, node.getEastNorth()))));
        }
        frontRiders.sort(Comparator.comparingDouble(r -> r.t));
        backRiders.sort(Comparator.comparingDouble(r -> r.t));

        // Move the walls
        final Collection<Command> commands = new ArrayList<>();
        for (int i = 0; i < walls.size(); i++) {
            Wall wall = walls.get(i);
            double f = (i + 1.0) / n;
            EastNorth newFront = enA.interpolate(enF, f);
            EastNorth newBack = enB.interpolate(enG, f);
            EastNorth oldFront = wall.front.getEastNorth();
            EastNorth oldBack = wall.back.getEastNorth();
            addMove(commands, wall.front, newFront);
            addMove(commands, wall.back, newBack);
            // intermediate wall nodes keep their relative position on the wall
            final Map<Node, Double> position = new HashMap<>();
            for (Node node : wall.interior) {
                position.put(node, projectionParameter(oldFront, oldBack, node.getEastNorth()));
            }
            wall.interior.sort(Comparator.comparingDouble(position::get));
            for (Node node : wall.interior) {
                addMove(commands, node, newFront.interpolate(newBack, position.get(node)));
            }
        }

        // Rebuild the outlines: intermediate nodes may have changed to the neighbouring building.
        for (int i = 0; i < n; i++) {
            final List<Node> nodes = new ArrayList<>();
            final Wall left = i > 0 ? walls.get(i - 1) : null;
            final Wall right = i < n - 1 ? walls.get(i) : null;
            final double from = (double) i / n;
            final double to = (i + 1.0) / n;
            // front, in ring order (from the last building towards the first one)
            nodes.add(right != null ? right.front : cornerF);
            for (int j = frontRiders.size() - 1; j >= 0; j--) {
                if (isInside(frontRiders.get(j).t, from, to, i, n))
                    nodes.add(frontRiders.get(j).node);
            }
            // left wall or left end
            if (left != null) {
                nodes.add(left.front);
                nodes.addAll(left.interior);
                nodes.add(left.back);
            } else {
                for (int j = ia; j <= ib; j++) {
                    nodes.add(ring.get(j));
                }
            }
            // back, in ring order (from the first building towards the last one)
            for (Rider rider : backRiders) {
                if (isInside(rider.t, from, to, i, n))
                    nodes.add(rider.node);
            }
            // right wall or right end
            if (right != null) {
                nodes.add(right.back);
                for (int j = right.interior.size() - 1; j >= 0; j--) {
                    nodes.add(right.interior.get(j));
                }
            } else {
                // cornerF is already the first node of the list
                for (int j = ig; j < iF; j++) {
                    nodes.add(ring.get(j));
                }
            }
            nodes.add(nodes.get(0));

            Way way = order.get(i);
            if (!isSameRing(way.getNodes(), nodes)) {
                // keep orientation and start node of the way to keep the change small
                if (isClockwise(way.getNodes()) != isClockwise(nodes)) {
                    Collections.reverse(nodes);
                }
                nodes.remove(nodes.size() - 1);
                int start = nodes.indexOf(way.firstNode());
                if (start > 0) {
                    Collections.rotate(nodes, -start);
                }
                nodes.add(nodes.get(0));
                commands.add(new ChangeNodesCommand(way, nodes));
            }
        }

        if (commands.isEmpty())
            return null;
        return new SequenceCommand(tr("Equalize terrace"), commands);
    }

    /**
     * Orders the buildings along the row. Two buildings are adjacent if they share at
     * least two nodes.
     *
     * @param ways the buildings
     * @return the buildings ordered from one end of the row to the other
     * @throws InvalidSelectionException if the buildings do not form a single row
     */
    private static List<Way> orderRow(List<Way> ways) throws InvalidSelectionException {
        final int n = ways.size();
        final List<List<Integer>> adjacent = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacent.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Set<Node> common = new HashSet<>(ways.get(i).getNodes());
                common.retainAll(ways.get(j).getNodes());
                if (common.size() >= 2) {
                    adjacent.get(i).add(j);
                    adjacent.get(j).add(i);
                } else if (common.size() == 1) {
                    throw new InvalidSelectionException(tr("{0} and {1} touch each other in a single node only.",
                            name(ways.get(i)), name(ways.get(j))));
                }
            }
        }
        int start = -1;
        for (int i = 0; i < n; i++) {
            int degree = adjacent.get(i).size();
            if (degree == 0)
                throw new InvalidSelectionException(tr("{0} does not share a wall with another selected building.",
                        name(ways.get(i))));
            if (degree > 2)
                throw new InvalidSelectionException(tr("{0} shares walls with more than two other buildings.",
                        name(ways.get(i))));
            if (degree == 1 && start < 0) {
                start = i;
            }
        }
        if (start < 0)
            throw new InvalidSelectionException(tr("The buildings form a closed ring instead of a row."));

        final List<Way> order = new ArrayList<>(n);
        int previous = -1;
        int current = start;
        while (true) {
            order.add(ways.get(current));
            int next = -1;
            for (int candidate : adjacent.get(current)) {
                if (candidate != previous) {
                    next = candidate;
                }
            }
            if (next < 0)
                break;
            previous = current;
            current = next;
        }
        if (order.size() != n)
            throw new InvalidSelectionException(tr("The buildings do not form a single row."));
        return order;
    }

    /**
     * Builds the outline of the row: all edges belonging to exactly one building, chained
     * to a closed ring.
     *
     * @param ways the buildings
     * @return the nodes of the outline in order (without the repeated closing node)
     * @throws InvalidSelectionException if the outline is not a single simple ring
     */
    private static List<Node> buildRing(List<Way> ways) throws InvalidSelectionException {
        final Map<Pair<Node, Node>, Integer> edgeCount = new HashMap<>();
        for (Way w : ways) {
            for (Pair<Node, Node> pair : w.getNodePairs(false)) {
                edgeCount.merge(normalize(pair), 1, Integer::sum);
            }
        }
        final Map<Node, List<Node>> neighbours = new HashMap<>();
        for (Map.Entry<Pair<Node, Node>, Integer> entry : edgeCount.entrySet()) {
            if (entry.getValue() > 2)
                throw new InvalidSelectionException(tr("The buildings overlap each other."));
            if (entry.getValue() == 1) {
                neighbours.computeIfAbsent(entry.getKey().a, k -> new ArrayList<>()).add(entry.getKey().b);
                neighbours.computeIfAbsent(entry.getKey().b, k -> new ArrayList<>()).add(entry.getKey().a);
            }
        }
        for (Map.Entry<Node, List<Node>> entry : neighbours.entrySet()) {
            if (entry.getValue().size() != 2)
                throw new InvalidSelectionException(tr("The outline of the row is not a simple polygon."));
        }
        final List<Node> ring = new ArrayList<>(neighbours.size());
        Node startNode = neighbours.keySet().iterator().next();
        Node previous = null;
        Node current = startNode;
        do {
            ring.add(current);
            List<Node> next = neighbours.get(current);
            Node candidate = next.get(0) == previous ? next.get(1) : next.get(0);
            previous = current;
            current = candidate;
        } while (current != startNode && ring.size() <= neighbours.size());
        if (ring.size() != neighbours.size())
            throw new InvalidSelectionException(tr("The outline of the row is not a simple polygon."));
        return ring;
    }

    private static String name(Way w) {
        return w.getDisplayName(DefaultNameFormatter.getInstance());
    }

    private static Pair<Node, Node> normalize(Pair<Node, Node> pair) {
        return pair.a.getUniqueId() <= pair.b.getUniqueId() ? new Pair<>(pair.a, pair.b) : new Pair<>(pair.b, pair.a);
    }

    /**
     * Checks that all ring nodes with index in [from, to] are nodes of the given way and
     * that the range is not empty.
     */
    private static boolean allBelongTo(List<Node> ring, int from, int to, Way way) {
        if (from > to)
            return false;
        for (int i = from; i <= to; i++) {
            if (!way.containsNode(ring.get(i)))
                return false;
        }
        return true;
    }

    /**
     * Finds the first corner of the ring in the index range [from, to], searching forwards
     * ({@code direction == 1}, starting at {@code from}) or backwards ({@code direction == -1},
     * starting at {@code to}).
     *
     * @return the index of the corner, or -1 if there is none
     */
    private static int firstCorner(List<Node> ring, int from, int to, int direction) {
        int size = ring.size();
        for (int step = 0; step <= to - from; step++) {
            int i = direction > 0 ? from + step : to - step;
            EastNorth prev = ring.get((i - 1 + size) % size).getEastNorth();
            EastNorth curr = ring.get(i).getEastNorth();
            EastNorth next = ring.get((i + 1) % size).getEastNorth();
            if (TerracerAction.turnAngle(prev, curr, next) >= Math.toRadians(TerracerAction.CORNER_ANGLE_DEGREES))
                return i;
        }
        return -1;
    }

    /**
     * Returns the parameter t of the orthogonal projection of p onto the line a-b,
     * such that a + t * (b - a) is the projected point.
     */
    private static double projectionParameter(EastNorth a, EastNorth b, EastNorth p) {
        double dx = b.east() - a.east();
        double dy = b.north() - a.north();
        double len2 = dx * dx + dy * dy;
        if (len2 == 0)
            return 0;
        return ((p.east() - a.east()) * dx + (p.north() - a.north()) * dy) / len2;
    }

    private static double clamp(double t) {
        return Math.max(0, Math.min(1, t));
    }

    /**
     * Checks whether the position t along the row belongs to building i of n. The last
     * building also gets nodes at exactly its right end, the others nodes at exactly
     * their left end.
     */
    private static boolean isInside(double t, double from, double to, int i, int n) {
        return t >= from && (t < to || (i == n - 1 && t <= to));
    }

    private static void addMove(Collection<Command> commands, Node node, EastNorth target) {
        EastNorth current = node.getEastNorth();
        if (!current.equalsEpsilon(target, 1e-6)) {
            commands.add(new MoveCommand(node, target.east() - current.east(), target.north() - current.north()));
        }
    }

    /**
     * Checks whether two closed node lists describe the same ring, ignoring the start node
     * and the orientation.
     */
    private static boolean isSameRing(List<Node> a, List<Node> b) {
        if (a.size() != b.size())
            return false;
        List<Node> ra = a.subList(0, a.size() - 1);
        List<Node> rb = new ArrayList<>(b.subList(0, b.size() - 1));
        for (int pass = 0; pass < 2; pass++) {
            int offset = rb.indexOf(ra.get(0));
            if (offset >= 0) {
                boolean same = true;
                for (int i = 0; i < ra.size() && same; i++) {
                    same = ra.get(i) == rb.get((offset + i) % rb.size());
                }
                if (same)
                    return true;
            }
            Collections.reverse(rb);
        }
        return false;
    }

    private static boolean isClockwise(List<Node> nodes) {
        double sum = 0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            EastNorth a = nodes.get(i).getEastNorth();
            EastNorth b = nodes.get(i + 1).getEastNorth();
            sum += (b.east() - a.east()) * (b.north() + a.north());
        }
        return sum > 0;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(getLayerManager().getEditDataSet() != null);
    }
}
