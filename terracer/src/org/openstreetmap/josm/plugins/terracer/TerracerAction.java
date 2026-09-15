// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.terracer;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Tag;
import org.openstreetmap.josm.data.osm.TagCollection;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.conflict.tags.CombinePrimitiveResolverDialog;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Pair;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.UserCancelException;
import org.openstreetmap.josm.tools.Utils;

/**
 * Terraces a quadrilateral, closed way into a series of quadrilateral,
 * closed ways. If two ways are selected and one of them can be identified as
 * a street (highway=*, name=*) then the given street will be added
 * to the 'associatedStreet' relation.
 * <p>
 *
 * At present it only works on quadrilaterals, but there is no reason
 * why it couldn't be extended to work with other shapes too. The
 * algorithm employed is naive, but it works in the simple case.
 *
 * @author zere - Copyright 2009 CloudMade Ltd
 */
public final class TerracerAction extends JosmAction {
    private static final String BUILDING = "building";
    private static final String ADDR_HOUSENUMBER = "addr:housenumber";
    private static final String ADDR_STREET = "addr:street";

    /**
     * Minimum change of direction (in degrees) at a node of the outline to be treated as a
     * corner. Nodes carrying tags or belonging to other ways (entrances, footway junctions)
     * with a smaller change of direction are treated as lying on a straight wall: they are
     * ignored when the sides of the outline are determined and inserted into the new
     * buildings afterwards.
     */
    static final double CORNER_ANGLE_DEGREES = 30;

    private Collection<Command> commands;
    private Collection<OsmPrimitive> primitives;
    private TagCollection tagsInConflict;

    public TerracerAction() {
        super(tr("Terrace a building"), "terrace",
                tr("Creates individual buildings from a long building."),
                Shortcut.registerShortcut("tools:Terracer", tr("More tools: {0}",
                        tr("Terrace a building")), KeyEvent.VK_T,
                        Shortcut.SHIFT), true);
    }

    private static Set<Relation> findAssociatedStreets(Collection<OsmPrimitive> objects) {
        Set<Relation> result = new HashSet<>();
        if (objects != null) {
            for (OsmPrimitive c : objects) {
                if (c != null) {
                    for (OsmPrimitive p : c.getReferrers()) {
                        if (p instanceof Relation && "associatedStreet".equals(p.get("type"))) {
                            result.add((Relation) p);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static final class InvalidUserInputException extends Exception {
        InvalidUserInputException(String message) {
            super(message);
        }
    }

    /**
     * Checks that the selection is OK. If not, displays error message. If so
     * calls to terraceBuilding(), which does all the real work.
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        cleanup();
        Collection<OsmPrimitive> sel = getLayerManager().getEditDataSet().getSelected();
        Way outline = null;
        Way street = null;
        String streetname = null;
        ArrayList<Node> housenumbers = new ArrayList<>();
        Node init = null;

        try {
            if (sel.size() == 1) {
                OsmPrimitive prim = sel.iterator().next();

                if (!(prim instanceof Way))
                    throw new InvalidUserInputException(prim+" is not a way");

                outline = (Way) prim;
            } else if (sel.size() > 1) {
                List<Way> ways = new ArrayList<>(Utils.filteredCollection(sel, Way.class));
                for (Way way : ways) {
                    if (way.hasKey(BUILDING)) {
                        if (outline != null)
                            // already have a building
                            throw new InvalidUserInputException("already have a building");
                        outline = way;
                    } else if (way.hasKey("highway")) {
                        if (street != null)
                            // already have a street
                            throw new InvalidUserInputException("already have a street");
                        street = way;
                        streetname = street.get("name");
                        if (streetname == null)
                            throw new InvalidUserInputException("street does not have any name");
                    } else
                        throw new InvalidUserInputException(way + " is neither a building nor a highway");
                }

                if (outline == null)
                    throw new InvalidUserInputException("no outline way found");

                List<Node> nodes = new ArrayList<>(Utils.filteredCollection(sel, Node.class));
                // Actually this should test if the selected address nodes lie
                // within the selected outline. Any ideas how to do this?
                for (Node node : nodes) {
                    if (node.hasKey(ADDR_HOUSENUMBER)) {
                        String nodesStreetName = node.get(ADDR_STREET);
                        // if a node has a street name if must be equal
                        // to the one of the other address nodes
                        if (nodesStreetName != null) {
                            if (streetname == null)
                                streetname = nodesStreetName;
                            else if (!nodesStreetName.equals(streetname))
                                throw new InvalidUserInputException("addr:street does not match street name");
                        }

                        housenumbers.add(node);
                    } else {
                        // A given node might not be an address node but then
                        // it has to be part of the building to help getting
                        // the number direction right.
                        if (!outline.containsNode(node) || init != null)
                            throw new InvalidUserInputException("node problem");
                        init = node;
                    }
                }

                housenumbers.sort(new HousenumberNodeComparator());
            }

            if (outline == null || !outline.isClosed() || outline.getNodesCount() < 5)
                throw new InvalidUserInputException("wrong or missing outline");

        } catch (InvalidUserInputException ex) {
            Logging.warn("Terracer: "+ex.getMessage());
            new ExtendedDialog(MainApplication.getMainFrame(), tr("Invalid selection"), "OK")
                .setButtonIcons("ok").setIcon(JOptionPane.INFORMATION_MESSAGE)
                .setContent(tr("Select a single, closed way of at least four nodes. " +
                    "(Optionally you can also select a street for the addr:street tag " +
                    "and a node to mark the start of numbering.)"))
                .showDialog();
            return;
        }

        Relation associatedStreet = null;

        // Try to find an associatedStreet relation that could be reused from housenumbers, outline and street.
        Set<OsmPrimitive> candidates = new HashSet<>(housenumbers);
        candidates.add(outline);
        if (street != null) {
            candidates.add(street);
        }

        Set<Relation> associatedStreets = findAssociatedStreets(candidates);

        if (!associatedStreets.isEmpty()) {
            associatedStreet = associatedStreets.iterator().next();
            if (associatedStreets.size() > 1) {
                // TODO: Deal with multiple associated Streets
                Logging.warn("Terracer: Found "+associatedStreets.size()+" associatedStreet relations. Considering the first one only.");
            }
        }

        if (streetname == null && associatedStreet != null && associatedStreet.hasKey("name")) {
            streetname = associatedStreet.get("name");
        }

        if (housenumbers.size() == 1) {
            // Special case of one outline and one address node.
            // Don't open the dialog
            terraceBuilding(outline, init, street, associatedStreet, 0, null, null, 0,
                    housenumbers, streetname, associatedStreet != null, false, "yes", false);
        } else {
            String title = trn("Change {0} object", "Change {0} objects", sel.size(), sel.size());
            // show input dialog.
            new HouseNumberInputHandler(this, outline, init, street, streetname, outline.get(BUILDING),
                    associatedStreet, housenumbers, title).dialog.showDialog();
        }
        cleanup();
    }

    private void cleanup() {
        commands = null;
        primitives = null;
        tagsInConflict = null;
    }

    private static Integer getNumber(String number) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Sorts the house number nodes according their numbers only
     * arguments are house number nodes
     */
    static class HousenumberNodeComparator implements Comparator<Node> {
        private static final Pattern PATTERN_HOUSE_NUMBER = Pattern.compile("^(\\d+)\\s*(.*)", Pattern.UNICODE_CHARACTER_CLASS);

        @Override
        public int compare(Node node1, Node node2) {
            // It's necessary to strip off trailing non-numbers so we can
            // compare the numbers itself numerically since string comparison
            // doesn't work for numbers with different number of digits,
            // e.g. 9 is higher than 11
            String node1String = node1.get(ADDR_HOUSENUMBER);
            String node2String = node2.get(ADDR_HOUSENUMBER);
            Matcher mat = PATTERN_HOUSE_NUMBER.matcher(node1String);
            if (mat.find()) {
                Integer node1Int = Integer.valueOf(mat.group(1));
                String node1Rest = mat.group(2);
                mat = PATTERN_HOUSE_NUMBER.matcher(node2String);
                if (mat.find()) {
                    Integer node2Int = Integer.valueOf(mat.group(1));
                    // If the numbers are the same, the rest has to make the decision,
                    // e.g. when comparing 23, 23a and 23b.
                    if (node1Int.equals(node2Int)) {
                      String node2Rest = mat.group(2);
                      return node1Rest.compareTo(node2Rest);
                    }

                    return node1Int.compareTo(node2Int);
                }
            }

            return node1String.compareTo(node2String);
        }
    }

    /**
     * Terraces a single, closed, quadrilateral way.
     * <p>
     * Any node must be adjacent to both a short and long edge, we naively
     * choose the longest edge and its opposite and interpolate along them
     * linearly to produce new nodes. Those nodes are then assembled into
     * closed, quadrilateral ways and left in the selection.
     *
     * @param outline The closed, quadrilateral way to terrace.
     * @param init The node that hints at which side to start the numbering
     * @param street The street, the buildings belong to (may be null)
     * @param associatedStreet associated street relation
     * @param segments The number of segments to generate
     * @param start Starting housenumber
     * @param end Ending housenumber
     * @param step The step width to use
     * @param housenumbers List of housenumbers to use. From and To are ignored
     *        if this is set.
     * @param streetName the name of the street, derived from the street line
     *        or the house numbers (may be null)
     * @param handleRelations If the user likes to add a relation or extend an
     *        existing relation
     * @param keepOutline If the outline way should be kept
     * @param buildingValue The value for {@code building} key to add
     * @param invertSide If true, splits on the short side instead of the long side
     */
    public void terraceBuilding(final Way outline, Node init, Way street, Relation associatedStreet, Integer segments,
                String start, String end, int step, List<Node> housenumbers, String streetName, boolean handleRelations,
                boolean keepOutline, String buildingValue, boolean invertSide) {
        final int nb;
        Integer to;
        Integer from = null;
        if (housenumbers == null || housenumbers.isEmpty()) {
            to = getNumber(end);
            from = getNumber(start);
            if (to != null && from != null) {
                nb = 1 + (to - from) / step;
            } else if (segments != null) {
                nb = segments;
            } else {
                // if we get here, there is is a bug in the input validation.
                throw new TerracerRuntimeException(
                        "Could not determine segments from parameters, this is a bug. "
                                + "Parameters were: segments " + segments
                                + " from " + from + " to " + to + " step " + step);
            }
        } else {
            nb = housenumbers.size();
        }

        // Nodes of the outline carrying information (tags or other ways), e.g. entrances or
        // junctions of footways. They must survive and end up in the new buildings.
        final List<Node> featureNodes = findFeatureNodes(outline);

        // now find which is the longest side connecting the first node, ignoring feature
        // nodes lying on a straight wall
        Pair<Way, Way> interp = findFrontAndBack(simplifiedOutline(outline, featureNodes), invertSide);

        final boolean swap = init != null && (init.equals(interp.a.lastNode()) || init.equals(interp.b.lastNode()));

        final double frontLength = wayLength(interp.a);
        final double backLength = wayLength(interp.b);

        // new nodes array to hold all intermediate nodes
        // This set will contain at least 4 existing nodes from the original outline
        // (those, which coordinates match coordinates of outline nodes)
        Node[][] newNodes = new Node[2][nb + 1];
        // This list will contain nodes of the outline that are used in new lines.
        // These nodes will not be deleted with the outline (if deleting was prompted).
        List<Node> reusedNodes = new ArrayList<>();

        this.commands = new LinkedList<>();
        Collection<Way> ways = new LinkedList<>();
        DataSet ds = getLayerManager().getEditDataSet();

        if (nb > 1) {
            // ways sharing nodes with the outline, e.g. adjacent buildings
            final Collection<Way> neighbours = findNeighbours(outline);

            // add required new nodes and build list of nodes to reuse
            for (int i = 0; i <= nb; ++i) {
                int iDir = swap ? nb - i : i;
                newNodes[0][i] = interpolateAlong(interp.a, frontLength * iDir / nb);
                newNodes[1][i] = interpolateAlong(interp.b, backLength * iDir / nb);
                for (int side = 0; side < 2; side++) {
                    // reuse existing nodes at the same position instead of creating duplicates
                    Node n = reuseFeatureNode(newNodes[side][i], featureNodes);
                    n = reuseNeighbourNode(n, neighbours);
                    newNodes[side][i] = n;
                    if (n.getDataSet() == null)
                        this.commands.add(new AddCommand(ds, n));
                    else if (outline.containsNode(n))
                        reusedNodes.add(n);
                }
            }

            // new nodes lying exactly on a neighbouring way are inserted into that way as well
            connectToNeighbours(newNodes, neighbours);

            // assemble new quadrilateral, closed ways
            final List<Way> terraces = new ArrayList<>(nb);
            for (int i = 0; i < nb; ++i) {
                final Way terr;
                boolean createNewWay = i > 0 || keepOutline;
                if (createNewWay) {
                    terr = new Way();
                    // add the tags of the outline to each building (e.g. source=*)
                    TagCollection.from(outline).applyTo(terr);
                } else {
                    terr = new Way(outline);
                    terr.setNodes(null);
                }

                terr.addNode(newNodes[0][i]);
                terr.addNode(newNodes[0][i + 1]);
                terr.addNode(newNodes[1][i + 1]);
                terr.addNode(newNodes[1][i]);
                terr.addNode(newNodes[0][i]);
                terraces.add(terr);
            }

            // put entrances etc. back onto the wall of the building they belong to
            insertFeatureNodes(terraces, featureNodes, reusedNodes);

            for (int i = 0; i < nb; ++i) {
                final Way terr = terraces.get(i);
                boolean createNewWay = i > 0 || keepOutline;

                addressBuilding(terr, street, streetName, associatedStreet, housenumbers, i,
                        from != null ? Integer.toString(from + i * step) : null, buildingValue);

                if (createNewWay) {
                    ways.add(terr);
                    this.commands.add(new AddCommand(ds, terr));
                } else {
                    ways.add(outline);
                    this.commands.add(new ChangeCommand(outline, terr));
                }
            }

            if (!keepOutline) {
                // Delete outline nodes having no tags and referrers but the outline itself
                List<Node> nodes = outline.getNodes();
                ArrayList<Node> nodesToDelete = new ArrayList<>();
                for (Node n : nodes) {
                    if (!n.hasKeys() && n.getReferrers().size() == 1 && !reusedNodes.contains(n))
                        nodesToDelete.add(n);
                }
                if (!nodesToDelete.isEmpty())
                    this.commands.add(DeleteCommand.delete(nodesToDelete));
            }
        } else {
            // Single building, just add the address details
            addressBuilding(outline, street, streetName, associatedStreet, housenumbers, 0, start, buildingValue);
            ways.add(outline);
        }

        // Remove the address nodes since their tags have been incorporated into the terraces.
        // Or should removing them also be an option?
        if (housenumbers != null && !housenumbers.isEmpty()) {
            commands.add(DeleteCommand.delete(housenumbers, true, true));
        }

        if (handleRelations) { // create a new relation or merge with existing
            if (associatedStreet == null) {  // create a new relation
                addNewAssociatedStreetRelation(street, streetName, ways);
            } else { // relation exists already - add new members
                updateAssociatedStreetRelation(associatedStreet, housenumbers, ways);
            }
        }

        UndoRedoHandler.getInstance().add(createTerracingCommand(outline));
        if (nb <= 1 && street != null) {
            // Select the way (for quick selection of a new house (with the same way))
            MainApplication.getLayerManager().getEditDataSet().setSelected(street);
        } else {
            // Select the new building outlines (for quick reversing)
            MainApplication.getLayerManager().getEditDataSet().setSelected(ways);
        }
    }

    private void updateAssociatedStreetRelation(Relation associatedStreet, List<Node> housenumbers, Collection<Way> ways) {
        Relation newAssociatedStreet = new Relation(associatedStreet);
        // remove housenumbers as they have been deleted
        newAssociatedStreet.removeMembersFor(housenumbers);
        // see #19853 avoid to duplicate existing members
        newAssociatedStreet.removeMembersFor(ways);
        for (Way w : ways) {
            newAssociatedStreet.addMember(new RelationMember("house", w));
        }
        /*if (!keepOutline) {
            newAssociatedStreet.removeMembersFor(outline);
        }*/
        this.commands.add(new ChangeCommand(associatedStreet, newAssociatedStreet));
    }

    private void addNewAssociatedStreetRelation(Way street, String streetName, Collection<Way> ways) {
        Relation associatedStreet = new Relation();
        associatedStreet.put("type", "associatedStreet");
        if (street != null) { // a street was part of the selection
            associatedStreet.put("name", street.get("name"));
            associatedStreet.addMember(new RelationMember("street", street));
        } else {
            associatedStreet.put("name", streetName);
        }
        for (Way w : ways) {
            associatedStreet.addMember(new RelationMember("house", w));
        }
        this.commands.add(new AddCommand(getLayerManager().getEditDataSet(), associatedStreet));
    }

    private Command createTerracingCommand(final Way outline) {
        return new SequenceCommand(tr("Terrace"), commands) {
            @Override
            public boolean executeCommand() {
                boolean result = super.executeCommand();
                if (result && tagsInConflict != null) {
                    try {
                        // Build conflicts commands only after all primitives have been added to dataset to fix #8942
                        List<Command> conflictCommands = CombinePrimitiveResolverDialog.launchIfNecessary(
                                tagsInConflict, primitives, Collections.singleton(outline));
                        if (!conflictCommands.isEmpty()) {
                            List<Command> newCommands = new ArrayList<>(commands);
                            newCommands.addAll(conflictCommands);
                            setSequence(newCommands.toArray(new Command[0]));
                            // Run conflicts commands
                            for (int i = 0; i < conflictCommands.size(); i++) {
                                result = conflictCommands.get(i).executeCommand();
                                if (!result && !continueOnError) {
                                    setSequenceComplete(false);
                                    undoCommands(commands.size()+i-1);
                                    return false;
                                }
                            }
                        }
                    } catch (UserCancelException e) {
                        Logging.trace(e);
                    }
                }
                return result;
            }
        };
    }

    /**
     * Adds address details to a single building
     *
     * @param outline The closed, quadrilateral way to add the address to.
     * @param street The street, the buildings belong to (may be null)
     * @param streetName the name of a street (may be null). Used if not null and street is null.
     * @param associatedStreet The associated street. Used to determine if addr:street should be set or not.
     * @param buildingValue The value for {@code building} key to add
     * @param houseNumbers The house numbers to use
     * @param i The index to use in {@code houseNumbers} for a replacement house number (preferential)
     * @param defaultNumber The number to use if there was not an underlying house number
     */
    private void addressBuilding(Way outline, Way street, String streetName, Relation associatedStreet,
            List<Node> houseNumbers, int i, String defaultNumber, String buildingValue) {
        Node houseNum = (houseNumbers != null && i >= 0 && i < houseNumbers.size()) ? houseNumbers.get(i) : null;
        boolean buildingAdded = false;
        boolean numberAdded = false;
        Map<String, String> tags = new HashMap<>();
        if (houseNum != null) {
            primitives = Arrays.asList(houseNum, outline);

            TagCollection tagsToCopy = TagCollection.unionOfAllPrimitives(primitives).getTagsFor(houseNum.keySet());
            tagsInConflict = tagsToCopy.getTagsFor(tagsToCopy.getKeysWithMultipleValues());
            tagsToCopy = tagsToCopy.minus(tagsInConflict).minus(TagCollection.from(outline));

            for (Tag tag : tagsToCopy) {
                tags.put(tag.getKey(), tag.getValue());
            }

            buildingAdded = houseNum.hasKey(BUILDING);
            numberAdded = houseNum.hasKey(ADDR_HOUSENUMBER);
        }
        if (!buildingAdded && buildingValue != null && !buildingValue.isEmpty()) {
            tags.put(BUILDING, buildingValue);
        }
        if (defaultNumber != null && !numberAdded) {
            tags.put(ADDR_HOUSENUMBER, defaultNumber);
        }
        // Only put addr:street if no relation exists or if it has no name
        if (associatedStreet == null || !associatedStreet.hasKey("name")) {
            if (street != null) {
                tags.put(ADDR_STREET, street.get("name"));
            } else if (streetName != null && !streetName.trim().isEmpty()) {
                tags.put(ADDR_STREET, streetName.trim());
            }
        }
        if (!tags.isEmpty()) {
            commands.add(new ChangePropertyCommand(getLayerManager().getEditDataSet(), Collections.singleton(outline), tags));
        }
    }

    /**
     * Collects the nodes of the outline that must survive terracing because they carry tags
     * or belong to other ways as well (entrances, junctions of footways, ...).
     *
     * @param outline the closed outline
     * @return the feature nodes in the order of the outline
     */
    private static List<Node> findFeatureNodes(Way outline) {
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < outline.getNodesCount() - 1; i++) {
            Node n = outline.getNode(i);
            if (n.hasKeys() || n.getReferrers().size() > 1) {
                result.add(n);
            }
        }
        return result;
    }

    /**
     * Returns a copy of the outline without the feature nodes lying on a straight wall, so that
     * entrances etc. neither disturb the detection of the sides nor end up as corners of the
     * new buildings. Falls back to the outline itself if nothing is removed or if fewer than
     * four corners would remain.
     *
     * @param outline the closed outline
     * @param featureNodes the feature nodes of the outline, see {@link #findFeatureNodes}
     * @return the simplified closed outline
     */
    private static Way simplifiedOutline(Way outline, Collection<Node> featureNodes) {
        final int count = outline.getNodesCount() - 1;
        final List<Node> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Node n = outline.getNode(i);
            if (featureNodes.contains(n)) {
                EastNorth prev = outline.getNode((i - 1 + count) % count).getEastNorth();
                EastNorth next = outline.getNode((i + 1) % count).getEastNorth();
                if (turnAngle(prev, n.getEastNorth(), next) < Math.toRadians(CORNER_ANGLE_DEGREES)) {
                    continue;
                }
            }
            nodes.add(n);
        }
        if (nodes.size() < 4 || nodes.size() == count)
            return outline;
        Way shape = new Way();
        shape.setNodes(nodes);
        shape.addNode(nodes.get(0));
        return shape;
    }

    /**
     * Returns the absolute change of direction (in radians, 0..pi) at node b of the path a-b-c.
     */
    private static double turnAngle(EastNorth a, EastNorth b, EastNorth c) {
        double dx1 = b.east() - a.east();
        double dy1 = b.north() - a.north();
        double dx2 = c.east() - b.east();
        double dy2 = c.north() - b.north();
        return Math.abs(Math.atan2(dx1 * dy2 - dy1 * dx2, dx1 * dx2 + dy1 * dy2));
    }

    /**
     * If a newly interpolated node coincides with a feature node, the feature node is used
     * instead so that no duplicate node is created next to it.
     */
    private static Node reuseFeatureNode(Node interpolated, Collection<Node> featureNodes) {
        for (Node f : featureNodes) {
            if (f != interpolated && f.equalsEpsilon(interpolated, ILatLon.MAX_SERVER_PRECISION))
                return f;
        }
        return interpolated;
    }

    /**
     * Inserts the feature nodes of the outline into the new buildings. Each node is inserted
     * into the segment nearest to it; several nodes in one segment are ordered along it.
     *
     * @param terraces the new closed ways
     * @param featureNodes the feature nodes of the outline
     * @param reusedNodes outline nodes already used as corners of the new ways
     */
    private static void insertFeatureNodes(List<Way> terraces, Collection<Node> featureNodes, Collection<Node> reusedNodes) {
        // way -> segment index -> nodes with their position along the segment
        final Map<Way, Map<Integer, List<Pair<Double, Node>>>> insertions = new HashMap<>();
        for (Node n : featureNodes) {
            if (reusedNodes.contains(n))
                continue;
            final EastNorth p = n.getEastNorth();
            Way bestWay = null;
            int bestSegment = -1;
            double bestPosition = 0;
            double bestDistance = Double.MAX_VALUE;
            for (Way w : terraces) {
                if (w.containsNode(n)) {
                    bestWay = null;
                    break;
                }
                for (int i = 0; i < w.getNodesCount() - 1; i++) {
                    EastNorth a = w.getNode(i).getEastNorth();
                    EastNorth b = w.getNode(i + 1).getEastNorth();
                    double distance = Geometry.closestPointToSegment(a, b, p).distanceSq(p);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestWay = w;
                        bestSegment = i;
                        bestPosition = positionOnSegment(a, b, p);
                    }
                }
            }
            if (bestWay != null) {
                insertions.computeIfAbsent(bestWay, k -> new HashMap<>())
                          .computeIfAbsent(bestSegment, k -> new ArrayList<>())
                          .add(new Pair<>(bestPosition, n));
            }
        }
        for (Map.Entry<Way, Map<Integer, List<Pair<Double, Node>>>> entry : insertions.entrySet()) {
            final Way w = entry.getKey();
            final List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < w.getNodesCount() - 1; i++) {
                nodes.add(w.getNode(i));
                List<Pair<Double, Node>> toInsert = entry.getValue().get(i);
                if (toInsert != null) {
                    toInsert.sort(Comparator.comparingDouble(pair -> pair.a));
                    for (Pair<Double, Node> pair : toInsert) {
                        nodes.add(pair.b);
                    }
                }
            }
            nodes.add(nodes.get(0));
            w.setNodes(nodes);
        }
    }

    /**
     * Returns the parameter t of the orthogonal projection of p onto the segment a-b,
     * such that a + t * (b - a) is the projected point.
     */
    private static double positionOnSegment(EastNorth a, EastNorth b, EastNorth p) {
        double dx = b.east() - a.east();
        double dy = b.north() - a.north();
        double len2 = dx * dx + dy * dy;
        return len2 == 0 ? 0 : ((p.east() - a.east()) * dx + (p.north() - a.north()) * dy) / len2;
    }

    /**
     * Finds the ways sharing at least one node with the outline, e.g. adjacent buildings.
     *
     * @param outline the closed outline
     * @return the neighbouring ways, without the outline itself
     */
    private static Collection<Way> findNeighbours(Way outline) {
        final Set<Way> neighbours = new LinkedHashSet<>();
        for (Node n : outline.getNodes()) {
            for (OsmPrimitive p : n.getReferrers()) {
                if (p instanceof Way && p != outline && !p.isDeleted()) {
                    neighbours.add((Way) p);
                }
            }
        }
        return neighbours;
    }

    /**
     * If a newly interpolated node coincides with a node of a neighbouring way, that node is
     * used instead so that the new building is connected to the neighbour.
     */
    private static Node reuseNeighbourNode(Node interpolated, Collection<Way> neighbours) {
        if (interpolated.getDataSet() != null)
            return interpolated;
        for (Way w : neighbours) {
            for (Node n : w.getNodes()) {
                if (n.equalsEpsilon(interpolated, ILatLon.MAX_SERVER_PRECISION))
                    return n;
            }
        }
        return interpolated;
    }

    /**
     * Inserts the new nodes lying exactly on a segment of a neighbouring way into that way,
     * so that e.g. an adjacent building sharing a wall with the outline stays connected to
     * the new buildings.
     *
     * @param newNodes the new nodes of the terrace (front and back)
     * @param neighbours the neighbouring ways, see {@link #findNeighbours}
     */
    private void connectToNeighbours(Node[][] newNodes, Collection<Way> neighbours) {
        if (neighbours.isEmpty())
            return;
        final Projection projection = ProjectionRegistry.getProjection();
        // way -> segment index -> nodes with their position along the segment
        final Map<Way, Map<Integer, List<Pair<Double, Node>>>> insertions = new HashMap<>();
        for (Node[] side : newNodes) {
            for (Node n : side) {
                if (n.getDataSet() != null)
                    continue; // existing node, already connected
                final EastNorth p = n.getEastNorth();
                for (Way w : neighbours) {
                    for (int i = 0; i < w.getNodesCount() - 1; i++) {
                        EastNorth a = w.getNode(i).getEastNorth();
                        EastNorth b = w.getNode(i + 1).getEastNorth();
                        EastNorth closest = Geometry.closestPointToSegment(a, b, p);
                        if (projection.eastNorth2latlon(closest).equalsEpsilon(n, ILatLon.MAX_SERVER_PRECISION)) {
                            insertions.computeIfAbsent(w, k -> new HashMap<>())
                                      .computeIfAbsent(i, k -> new ArrayList<>())
                                      .add(new Pair<>(positionOnSegment(a, b, p), n));
                            break;
                        }
                    }
                }
            }
        }
        for (Map.Entry<Way, Map<Integer, List<Pair<Double, Node>>>> entry : insertions.entrySet()) {
            final Way w = entry.getKey();
            final List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < w.getNodesCount(); i++) {
                nodes.add(w.getNode(i));
                List<Pair<Double, Node>> toInsert = entry.getValue().get(i);
                if (toInsert != null) {
                    toInsert.sort(Comparator.comparingDouble(pair -> pair.a));
                    for (Pair<Double, Node> pair : toInsert) {
                        nodes.add(pair.b);
                    }
                }
            }
            this.commands.add(new ChangeNodesCommand(w, nodes));
        }
    }

    /**
     * Creates a node at a certain distance along a way, as calculated by the
     * great circle distance.
     * <p>
     * Note that this really isn't an efficient way to do this and leads to
     * O(N^2) running time for the main algorithm, but its simple and easy
     * to understand, and probably won't matter for reasonable-sized ways.
     *
     * @param w The way to interpolate.
     * @param l The length at which to place the node.
     * @return A node at a distance l along w from the first point.
     */
    private static Node interpolateAlong(Way w, double l) {
        List<Pair<Node, Node>> pairs = w.getNodePairs(false);
        for (int i = 0; i < pairs.size(); ++i) {
            Pair<Node, Node> p = pairs.get(i);
            final double seg_length = p.a.greatCircleDistance(p.b);
            if (l <= seg_length || i == pairs.size() - 1) {
                // be generous on the last segment (numerical roudoff can lead to a small overshoot)
                return interpolateNode(p.a, p.b, l / seg_length);
            } else {
                l -= seg_length;
            }
        }
        // we shouldn't get here
        throw new IllegalStateException();
    }

    /**
     * Calculates the great circle length of a way by summing the great circle
     * distance of each pair of nodes.
     *
     * @param w The way to calculate length of.
     * @return The length of the way.
     */
    private static double wayLength(Way w) {
        double length = 0.0;
        for (Pair<Node, Node> p : w.getNodePairs(false)) {
            length += p.a.greatCircleDistance(p.b);
        }
        return length;
    }

    /**
     * Given a way, try and find a definite front and back by looking at the
     * segments to find the "sides". Sides are assumed to be single segments
     * which cannot be contiguous.
     *
     * @param w The way to analyse.
     * @param invertSide If true, forces the use of the alternative (adjacent) pair
     *                   of sides, effectively rotating the split axis.
     * @return A pair of ways (front, back) pointing in the same directions.
     */
    private static Pair<Way, Way> findFrontAndBack(Way w, boolean invertSide) {
        // calculate the "side-ness" score for each segment of the way
        double[] sideness = calculateSideness(w);

        // find the largest two sidenesses which are not contiguous
        int[] indexes = sortedIndexes(sideness);
        int side1 = indexes[0];
        int side2 = indexes[1];
        // if side2 is contiguous with side1 then look further down the
        // list. we know there are at least 4 sides, as anything smaller
        // than a quadrilateral would have been rejected at an earlier stage.
        if (indexDistance(side1, side2, indexes.length) < 2) {
            side2 = indexes[2];
        }
        if (indexDistance(side1, side2, indexes.length) < 2) {
            side2 = indexes[3];
        }

        // if the second side has a shorter length and an approximately equal
        // sideness then its better to choose the shorter, as with
        // quadrilaterals
        // created using the orthogonalise tool the sideness will be about the
        // same for all sides.
        if (sideLength(w, side1) > sideLength(w, side1 + 1)
                && Math.abs(sideness[side1] - sideness[(side1 + 1) % (w.getNodesCount() - 1)]) < 0.001) {
            side1 = (side1 + 1) % (w.getNodesCount() - 1);
            side2 = (side2 + 1) % (w.getNodesCount() - 1);
        }

        // if inverted, shift the selection to the adjacent segments to use the
        // alternative pair of sides (e.g. top/bottom instead of left/right).
        if (invertSide) {
            side1 = (side1 + 1) % (w.getNodesCount() - 1);
            side2 = (side2 + 1) % (w.getNodesCount() - 1);
        }

        // swap side1 and side2 into sorted order.
        if (side1 > side2) {
            int tmp = side2;
            side2 = side1;
            side1 = tmp;
        }

        Way front = new Way();
        Way back = new Way();
        for (int i = side2 + 1; i < w.getNodesCount() - 1; ++i) {
            front.addNode(w.getNode(i));
        }
        for (int i = 0; i <= side1; ++i) {
            front.addNode(w.getNode(i));
        }
        // add the back in reverse order so that the front and back ways point
        // in the same direction.
        for (int i = side2; i > side1; --i) {
            back.addNode(w.getNode(i));
        }

        return new Pair<>(front, back);
    }

    /**
     * returns the distance of two segments of a closed polygon
     * @param i1 The first segment index
     * @param i2 The second segment index
     * @param n The number of segments in the polygon
     * @return The distance between the two segments
     */
    private static int indexDistance(int i1, int i2, int n) {
        return Math.min(positiveModulus(i1 - i2, n), positiveModulus(i2 - i1, n));
    }

    /**
     * return the modulus in the range [0, n)
     * @param a dividend
     * @param n divisor
     * @return The positive modulus (if {@code a} is negative)
     */
    private static int positiveModulus(int a, int n) {
        if (n <= 0)
            throw new IllegalArgumentException();
        int res = a % n;
        if (res < 0) {
            res += n;
        }
        return res;
    }

    /**
     * Calculate the length of a side (from node i to i+1) in a way. This assumes that
     * the way is closed, but I only ever call it for buildings.
     * @param w The way
     * @param i The side (0 indexed)
     * @return The length of that way segment
     */
    private static double sideLength(Way w, int i) {
        Node a = w.getNode(i);
        Node b = w.getNode((i + 1) % (w.getNodesCount() - 1));
        return a.greatCircleDistance(b);
    }

    /**
     * Given an array of doubles (but this could made generic very easily) sort
     * into order and return the array of indexes such that, for a returned array
     * x, a[x[i]] is sorted for ascending index i.
     * <p>
     * This isn't efficient at all, but should be fine for the small arrays we're
     * expecting. If this gets slow - replace it with some more efficient algorithm.
     *
     * @param a The array to sort.
     * @return An array of indexes, the same size as the input, such that a[x[i]]
     * is in sorted order.
     */
    private static int[] sortedIndexes(final double[] a) {

        final int length = a.length;
        ArrayList<SortWithIndex> sortable = new ArrayList<>(length);
        for (int i = 0; i < length; ++i) {
            sortable.add(new SortWithIndex(a[i], i));
        }
        Collections.sort(sortable);

        int[] indexes = new int[length];
        for (int i = 0; i < length; ++i) {
            indexes[i] = sortable.get(i).i;
        }

        return indexes;
    }

    /**
     * Calculate "sideness" metric for each segment in a way.
     * @param w The way to get the sideness metric for
     * @return The sideness for each segment of the way
     */
    private static double[] calculateSideness(Way w) {
        final int length = w.getNodesCount() - 1;
        double[] sideness = new double[length];

        sideness[0] = calculateSideness(w.getNode(length - 1), w.getNode(0), w
                .getNode(1), w.getNode(2));
        for (int i = 1; i < length - 1; ++i) {
            sideness[i] = calculateSideness(w.getNode(i - 1), w.getNode(i), w
                    .getNode(i + 1), w.getNode(i + 2));
        }
        sideness[length - 1] = calculateSideness(w.getNode(length - 2), w
                .getNode(length - 1), w.getNode(length), w.getNode(1));

        return sideness;
    }

    /**
     * Calculate sideness of a single segment given the nodes which make up that
     * segment and its previous and next segments in order. Sideness is calculated
     * for the segment b-c.
     * @param a The previous node
     * @param b The first node of the current segment
     * @param c The last node of the current segment
     * @param d The next node
     * @return the sideness
     */
    private static double calculateSideness(ILatLon a, ILatLon b, ILatLon c, ILatLon d) {
        final double ndx = b.lon() - a.lon();
        final double pdx = d.lon() - c.lon();
        final double ndy = b.lat() - a.lat();
        final double pdy = d.lat() - c.lat();

        return (ndx * pdx + ndy * pdy)
                / Math.sqrt((ndx * ndx + ndy * ndy) * (pdx * pdx + pdy * pdy));
    }

    /**
     * Creates a new node at the interpolated position between the argument
     * nodes. Interpolates linearly in projected coordinates.
     * <p>
     * If new node coordinate matches a or b coordinates, a or b is returned.
     *
     * @param a First node, at which f=0.
     * @param b Last node, at which f=1.
     * @param f Fractional position between first and last nodes.
     * @return A new node at the interpolated position (or a or b in case if f ≈ 0 or f ≈ 1).
     */
    private static Node interpolateNode(Node a, Node b, double f) {
        Node n = new Node(a.getEastNorth().interpolate(b.getEastNorth(), f));
        if (n.equalsEpsilon(a, ILatLon.MAX_SERVER_PRECISION))
            return a;
        if (n.equalsEpsilon(b, ILatLon.MAX_SERVER_PRECISION))
            return b;
        return n;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(getLayerManager().getEditDataSet() != null);
    }

    private static class SortWithIndex implements Comparable<SortWithIndex> {
        /**
         * The value to sort
         */
        private final double x;
        /**
         * The index in the original array
         */
        private final int i;

        SortWithIndex(double a, int b) {
            x = a;
            i = b;
        }

        @Override
        public int compareTo(SortWithIndex o) {
            return Double.compare(x, o.x);
        }
    }
}
