// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.LatLon;

/**
 * Reduces a list of STAC items to the most recent coverage.
 * <p>
 * Archives such as the DOP collection of the LGLN keep every flight, so the same place is covered by
 * items of many years. For the question how up to date the imagery is, only the newest item of a
 * place is interesting; older items are of interest only where they are not (completely) covered by
 * a newer one.
 */
public final class StacCoverage {

    /** Part of an item that has to stay uncovered for the item to be kept. */
    private static final double MIN_VISIBLE_FRACTION = 0.02;

    private StacCoverage() {
        // Hide public constructor of this utility class
    }

    /**
     * Filters the items that are visible in a view showing the most recent coverage.
     * @param newestFirst the items, sorted by date, newest first
     * @return the items that are not (almost) completely covered by newer items, newest first
     */
    public static List<StacItem> latest(List<StacItem> newestFirst) {
        List<StacItem> result = new ArrayList<>();
        Area covered = new Area();
        for (StacItem item : newestFirst) {
            Shape shape = toShape(item);
            Area remaining = new Area(shape);
            remaining.subtract(covered);
            if (remaining.isEmpty()) {
                continue;
            }
            double full = polygonArea(shape);
            if (full > 0 && polygonArea(remaining) < MIN_VISIBLE_FRACTION * full) {
                continue;
            }
            result.add(item);
            covered.add(new Area(shape));
        }
        return result;
    }

    /**
     * Returns the footprint of an item in geographic coordinates, x being the longitude.
     * @param item the item
     * @return the footprint as a shape
     */
    static Shape toShape(StacItem item) {
        Path2D path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (List<LatLon> ring : item.getRings()) {
            boolean first = true;
            for (LatLon point : ring) {
                if (first) {
                    path.moveTo(point.lon(), point.lat());
                    first = false;
                } else {
                    path.lineTo(point.lon(), point.lat());
                }
            }
            if (!first) {
                path.closePath();
            }
        }
        return path;
    }

    /**
     * Returns the area enclosed by a shape, in square degrees.
     * @param shape the shape
     * @return the absolute value of the enclosed area
     */
    static double polygonArea(Shape shape) {
        double sum = 0;
        double[] coordinates = new double[6];
        double startX = 0;
        double startY = 0;
        double previousX = 0;
        double previousY = 0;
        for (PathIterator it = shape.getPathIterator(null, 0); !it.isDone(); it.next()) {
            switch (it.currentSegment(coordinates)) {
                case PathIterator.SEG_MOVETO:
                    startX = coordinates[0];
                    startY = coordinates[1];
                    previousX = startX;
                    previousY = startY;
                    break;
                case PathIterator.SEG_LINETO:
                    sum += previousX * coordinates[1] - coordinates[0] * previousY;
                    previousX = coordinates[0];
                    previousY = coordinates[1];
                    break;
                case PathIterator.SEG_CLOSE:
                    sum += previousX * startY - startX * previousY;
                    previousX = startX;
                    previousY = startY;
                    break;
                default:
                    break;
            }
        }
        return Math.abs(sum) / 2;
    }
}
