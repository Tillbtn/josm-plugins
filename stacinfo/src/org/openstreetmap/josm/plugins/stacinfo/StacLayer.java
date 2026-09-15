// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.dialogs.LayerListPopup;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;

/**
 * Layer drawing the footprints of the STAC items of one source, coloured and labelled by their date.
 */
public class StacLayer extends Layer implements NavigatableComponent.ZoomChangeListener {

    /**
     * Notified when the items, the source or the status of the layer have changed.
     */
    public interface StacLayerListener {
        /**
         * Called on the EDT after the layer content changed.
         * @param layer the layer that changed
         */
        void stacLayerChanged(StacLayer layer);
    }

    private static final Color DEFAULT_COLOR = new Color(0xE8, 0x59, 0x0C);
    private static final Color HIGHLIGHT_COLOR = new Color(0xFF, 0xFF, 0xFF);
    private static final Color TEXT_COLOR = new Color(0x20, 0x20, 0x20);
    private static final double LOAD_MARGIN = 0.15;
    private static final int MIN_LABEL_WIDTH = 55;

    private final transient CopyOnWriteArrayList<StacLayerListener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> highlighted = new HashSet<>();
    private final Timer refreshTimer;

    private transient StacSource source;
    private transient StacFilter filter = StacFilter.EMPTY;
    private transient List<StacItem> allItems = Collections.emptyList();
    private transient List<StacItem> filteredItems = Collections.emptyList();
    private transient List<StacItem> latestItems = Collections.emptyList();
    private transient List<StacItem> paintOrder = Collections.emptyList();
    private boolean paintOrderLatestOnly = StacSettings.LATEST_ONLY.get();
    private transient StacSource loadedSource;
    private transient Bounds loadedBounds;
    private String status = "";
    private boolean loading;
    private int requestCounter;

    /**
     * Creates the layer for a STAC source.
     * @param source the source to query, must not be {@code null}
     */
    public StacLayer(StacSource source) {
        super(layerName(source));
        this.source = source;
        refreshTimer = new Timer(Math.max(100, StacSettings.REFRESH_DELAY.get()), e -> refresh(false));
        refreshTimer.setRepeats(false);
        NavigatableComponent.addZoomChangeListener(this);
    }

    private static String layerName(StacSource source) {
        return tr("Imagery dates: {0}", source == null ? tr("unknown") : source.getName());
    }

    /**
     * Returns the source currently shown.
     * @return the STAC source
     */
    public StacSource getSource() {
        return source;
    }

    /**
     * Switches the layer to another source and reloads the items.
     * @param newSource the source to show
     */
    public void setSource(StacSource newSource) {
        if (newSource == null || newSource.equals(source)) {
            return;
        }
        source = newSource;
        setName(layerName(newSource));
        setItems(Collections.emptyList());
        loadedBounds = null;
        loadedSource = null;
        highlighted.clear();
        refresh(true);
    }

    /**
     * Returns the items that are shown, that is either all items of the last query or only the most
     * recent coverage.
     * @return the shown items, newest first
     */
    public List<StacItem> getItems() {
        return StacSettings.LATEST_ONLY.get() ? latestItems : filteredItems;
    }

    /**
     * Returns all items of the last successful query, including the superseded ones.
     * @return the items, newest first
     */
    public List<StacItem> getAllItems() {
        return allItems;
    }

    /**
     * Returns the number of items that are hidden because a newer item covers them.
     * @return the number of hidden items, zero if all items are shown
     */
    public int getHiddenCount() {
        return StacSettings.LATEST_ONLY.get() ? filteredItems.size() - latestItems.size() : 0;
    }

    /**
     * Returns the number of items removed by the filter of the dialog.
     * @return the number of items that do not match the filter
     */
    public int getFilteredOutCount() {
        return allItems.size() - filteredItems.size();
    }

    /**
     * Returns the filter applied to the loaded items.
     * @return the current filter, never {@code null}
     */
    public StacFilter getFilter() {
        return filter;
    }

    /**
     * Restricts the shown items, without querying the endpoint again.
     * @param newFilter the filter to apply, {@link StacFilter#EMPTY} to show everything
     */
    public void setFilter(StacFilter newFilter) {
        filter = newFilter == null ? StacFilter.EMPTY : newFilter;
        applyFilter();
        update(status);
    }

    /**
     * Switches between the most recent coverage and all items of the query.
     * @param latestOnly {@code true} to hide items that are covered by a newer one
     */
    public void setLatestOnly(boolean latestOnly) {
        if (latestOnly == StacSettings.LATEST_ONLY.get()) {
            return;
        }
        StacSettings.LATEST_ONLY.put(latestOnly);
        updatePaintOrder();
        update(status);
    }

    private void setItems(List<StacItem> newAllItems) {
        allItems = newAllItems;
        applyFilter();
    }

    private void applyFilter() {
        filteredItems = filter.filter(allItems);
        latestItems = StacCoverage.latest(filteredItems);
        updatePaintOrder();
    }

    private void updatePaintOrder() {
        paintOrderLatestOnly = StacSettings.LATEST_ONLY.get();
        List<StacItem> ordered = new ArrayList<>(getItems());
        // The newest items have to be painted last so that they stay visible
        Collections.reverse(ordered);
        paintOrder = ordered;
    }

    /**
     * Returns the message shown in the map view, for example the reason why nothing is displayed.
     * @return the status message, possibly empty
     */
    public String getStatus() {
        return status;
    }

    /**
     * Determines whether a query is running.
     * @return {@code true} while the plugin waits for the STAC API
     */
    public boolean isLoading() {
        return loading;
    }

    /**
     * Marks items, for example because they are selected in the dialog.
     * @param ids the ids of the items to draw emphasized
     */
    public void setHighlighted(Collection<String> ids) {
        highlighted.clear();
        if (ids != null) {
            highlighted.addAll(ids);
        }
        invalidate();
    }

    /**
     * Registers a listener that is called whenever the content of the layer changes.
     * @param listener the listener to add
     */
    public void addStacLayerListener(StacLayerListener listener) {
        listeners.addIfAbsent(listener);
    }

    /**
     * Unregisters a listener.
     * @param listener the listener to remove
     */
    public void removeStacLayerListener(StacLayerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void zoomChanged() {
        if (StacSettings.AUTO_REFRESH.get() && isVisible()) {
            refreshTimer.setInitialDelay(Math.max(100, StacSettings.REFRESH_DELAY.get()));
            refreshTimer.restart();
        }
    }

    /**
     * Queries the STAC API for the current map view.
     * @param force if {@code true}, the items are reloaded even if the current view is already covered
     */
    public void refresh(boolean force) {
        if (source == null || !MainApplication.isDisplayingMapView()) {
            return;
        }
        Bounds view = MainApplication.getMap().mapView.getRealBounds();
        if (view == null) {
            return;
        }
        if (!force && source.equals(loadedSource) && loadedBounds != null && covers(loadedBounds, view)) {
            return;
        }
        double maxArea = StacSettings.MAX_AREA.get();
        if (view.getArea() > maxArea) {
            setItems(Collections.emptyList());
            loadedBounds = null;
            loadedSource = null;
            loading = false;
            update(tr("Zoom in to load the dates of ''{0}''", source.getName()));
            return;
        }
        final Bounds request = enlarge(view, LOAD_MARGIN);
        final StacSource requestedSource = source;
        final int token = ++requestCounter;
        loading = true;
        update(tr("Loading {0}...", source.getName()));
        final int maxItems = StacSettings.MAX_ITEMS.get();
        MainApplication.worker.submit(() -> {
            try {
                List<StacItem> result = StacClient.search(requestedSource, request, maxItems);
                GuiHelper.runInEDT(() -> finish(token, requestedSource, request, result, maxItems, null));
            } catch (IOException | RuntimeException e) {
                Logging.warn("stacinfo: query of " + requestedSource.getName() + " failed");
                Logging.warn(e);
                GuiHelper.runInEDT(() -> finish(token, requestedSource, null, Collections.emptyList(),
                        maxItems, messageOf(e)));
            }
        });
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private void finish(int token, StacSource requestedSource, Bounds requestedBounds, List<StacItem> result,
            int maxItems, String error) {
        if (token != requestCounter) {
            // A newer request has been started in the meantime
            return;
        }
        loading = false;
        if (error != null) {
            setItems(Collections.emptyList());
            loadedBounds = null;
            loadedSource = null;
            update(tr("Error: {0}", error));
            return;
        }
        setItems(result);
        loadedBounds = requestedBounds;
        loadedSource = requestedSource;
        if (result.isEmpty()) {
            update(tr("No data of ''{0}'' in this area", requestedSource.getName()));
        } else if (result.size() >= maxItems) {
            update(tr("Only the {0} newest items are shown, zoom in for the complete picture", maxItems));
        } else {
            update("");
        }
    }

    private void update(String newStatus) {
        status = newStatus;
        invalidate();
        listeners.forEach(listener -> listener.stacLayerChanged(this));
    }

    private static boolean covers(Bounds outer, Bounds inner) {
        return outer.getMinLat() <= inner.getMinLat() && outer.getMinLon() <= inner.getMinLon()
                && outer.getMaxLat() >= inner.getMaxLat() && outer.getMaxLon() >= inner.getMaxLon();
    }

    private static Bounds enlarge(Bounds bounds, double factor) {
        double latMargin = bounds.getHeight() * factor;
        double lonMargin = bounds.getWidth() * factor;
        return new Bounds(
                Math.max(-90, bounds.getMinLat() - latMargin),
                Math.max(-180, bounds.getMinLon() - lonMargin),
                Math.min(90, bounds.getMaxLat() + latMargin),
                Math.min(180, bounds.getMaxLon() + lonMargin));
    }

    @Override
    public void paint(Graphics2D graphics, MapView mv, Bounds box) {
        if (paintOrderLatestOnly != StacSettings.LATEST_ONLY.get()) {
            // The setting has been changed somewhere else, for example in the preferences
            updatePaintOrder();
        }
        List<StacItem> current = paintOrder;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            long[] range = dateRange(current);
            // Painted from the oldest to the newest item, so that the current state stays on top
            List<StacItem> visible = new ArrayList<>();
            for (StacItem item : current) {
                if (item.getBounds().intersects(box)) {
                    visible.add(item);
                    drawItem(g, mv, item, range);
                }
            }
            if (StacSettings.SHOW_LABELS.get()) {
                drawLabels(g, mv, visible);
            }
            if (StacSettings.SHOW_LEGEND.get() && !visible.isEmpty()) {
                drawLegend(g, mv, visible.size(), range);
            }
            drawStatus(g, mv);
        } finally {
            g.dispose();
        }
    }

    private void drawItem(Graphics2D g, MapView mv, StacItem item, long[] range) {
        boolean isHighlighted = highlighted.contains(item.getId());
        for (List<LatLon> ring : item.getRings()) {
            Path2D path = toPath(ring, mv);
            g.setColor(fillColor(item, range));
            g.fill(path);
            g.setStroke(new BasicStroke(isHighlighted ? 3f : 1f));
            g.setColor(isHighlighted ? HIGHLIGHT_COLOR : outlineColor(item, range));
            g.draw(path);
        }
    }

    private static Path2D toPath(List<LatLon> ring, MapView mv) {
        Path2D path = new Path2D.Double();
        boolean first = true;
        for (LatLon point : ring) {
            Point screen = mv.getPoint(point);
            if (first) {
                path.moveTo(screen.getX(), screen.getY());
                first = false;
            } else {
                path.lineTo(screen.getX(), screen.getY());
            }
        }
        path.closePath();
        return path;
    }

    private void drawLabels(Graphics2D g, MapView mv, List<StacItem> visible) {
        g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
        FontMetrics metrics = g.getFontMetrics();
        List<Rectangle> occupied = new ArrayList<>();
        // Newest first, so that a label of an older item is dropped where the labels overlap
        for (int index = visible.size() - 1; index >= 0; index--) {
            StacItem item = visible.get(index);
            String label = item.getDateLabel();
            if (label.isEmpty()) {
                continue;
            }
            Rectangle screen = screenBounds(item.getBounds(), mv);
            int textWidth = metrics.stringWidth(label);
            if (screen.width < Math.max(MIN_LABEL_WIDTH, textWidth + 6) || screen.height < metrics.getHeight() + 4) {
                continue;
            }
            int x = screen.x + (screen.width - textWidth) / 2;
            int y = screen.y + (screen.height + metrics.getAscent()) / 2;
            Rectangle box = new Rectangle(x - 2, y - metrics.getAscent() - 1, textWidth + 4, metrics.getHeight() + 2);
            if (occupied.stream().anyMatch(box::intersects)) {
                continue;
            }
            occupied.add(box);
            drawOutlinedString(g, label, x, y);
        }
    }

    private static void drawOutlinedString(Graphics2D g, String text, int x, int y) {
        g.setColor(Color.WHITE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    g.drawString(text, x + dx, y + dy);
                }
            }
        }
        g.setColor(TEXT_COLOR);
        g.drawString(text, x, y);
    }

    private static Rectangle screenBounds(Bounds bounds, MapView mv) {
        Point min = mv.getPoint(new LatLon(bounds.getMinLat(), bounds.getMinLon()));
        Point max = mv.getPoint(new LatLon(bounds.getMaxLat(), bounds.getMaxLon()));
        int x = Math.min(min.x, max.x);
        int y = Math.min(min.y, max.y);
        return new Rectangle(x, y, Math.abs(max.x - min.x), Math.abs(max.y - min.y));
    }

    private void drawLegend(Graphics2D g, MapView mv, int count, long[] range) {
        String title = source == null ? "" : source.getName();
        String oldest = formatEpoch(range[0]);
        String newest = formatEpoch(range[1]);
        int barWidth = 150;
        int barHeight = 10;
        int padding = 8;
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics metrics = g.getFontMetrics();
        int lineHeight = metrics.getHeight();
        int width = Math.max(barWidth, metrics.stringWidth(title)) + 2 * padding;
        int height = 2 * lineHeight + barHeight + 3 * padding;
        int x = 10;
        int y = mv.getHeight() - height - 10;

        g.setColor(new Color(255, 255, 255, 210));
        g.fillRect(x, y, width, height);
        g.setColor(new Color(120, 120, 120));
        g.drawRect(x, y, width, height);

        g.setColor(TEXT_COLOR);
        g.drawString(title, x + padding, y + padding + metrics.getAscent());

        int barY = y + padding + lineHeight + padding / 2;
        if (range[0] < range[1]) {
            g.setPaint(new GradientPaint(x + padding, barY, colorFor(0), x + padding + barWidth, barY, colorFor(1)));
        } else {
            g.setPaint(colorFor(1));
        }
        g.fillRect(x + padding, barY, barWidth, barHeight);
        g.setPaint(null);
        g.setColor(new Color(120, 120, 120));
        g.drawRect(x + padding, barY, barWidth, barHeight);

        g.setColor(TEXT_COLOR);
        int textY = barY + barHeight + metrics.getAscent() + 2;
        g.drawString(oldest, x + padding, textY);
        String right = range[0] < range[1] ? newest : "";
        g.drawString(right, x + padding + barWidth - metrics.stringWidth(right), textY);
        String countText = trn("{0} item", "{0} items", count, count);
        g.drawString(countText, x + width - padding - metrics.stringWidth(countText), y + padding + metrics.getAscent());
    }

    private void drawStatus(Graphics2D g, MapView mv) {
        if (status.isEmpty()) {
            return;
        }
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(status) + 16;
        int height = metrics.getHeight() + 8;
        int x = Math.max(0, (mv.getWidth() - width) / 2);
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(x, 10, width, height);
        g.setColor(new Color(120, 120, 120));
        g.drawRect(x, 10, width, height);
        g.setColor(TEXT_COLOR);
        g.drawString(status, x + 8, 10 + 4 + metrics.getAscent());
    }

    private static long[] dateRange(List<StacItem> items) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (StacItem item : items) {
            Instant date = item.getDate();
            if (date != null) {
                min = Math.min(min, date.toEpochMilli());
                max = Math.max(max, date.toEpochMilli());
            }
        }
        return min > max ? new long[] {0, 0} : new long[] {min, max};
    }

    private static String formatEpoch(long epochMilli) {
        return epochMilli == 0 ? "" : Instant.ofEpochMilli(epochMilli).toString().substring(0, 10);
    }

    private double relativeAge(StacItem item, long[] range) {
        Instant date = item.getDate();
        if (date == null || range[1] <= range[0]) {
            return 1;
        }
        return (date.toEpochMilli() - range[0]) / (double) (range[1] - range[0]);
    }

    private Color colorFor(double relativeAge) {
        Color base = source != null && source.getColor() != null ? source.getColor() : DEFAULT_COLOR;
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        // Old images are drawn pale, recent images in the full colour of the source
        float saturation = (float) (0.25 + 0.75 * relativeAge) * hsb[1];
        float brightness = (float) Math.min(1, hsb[2] + 0.35 * (1 - relativeAge));
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    private Color fillColor(StacItem item, long[] range) {
        Color color = colorFor(relativeAge(item, range));
        int alpha = Math.max(0, Math.min(255, StacSettings.FILL_ALPHA.get()));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private Color outlineColor(StacItem item, long[] range) {
        Color color = colorFor(relativeAge(item, range));
        return color.darker();
    }

    @Override
    public Icon getIcon() {
        return ImageProvider.get("dialogs", "stacinfo", ImageProvider.ImageSizes.LAYER);
    }

    @Override
    public String getToolTipText() {
        if (source == null) {
            return tr("Imagery dates");
        }
        List<StacItem> shown = getItems();
        return tr("Imagery dates of ''{0}'' ({1})", source.getName(),
                trn("{0} item", "{0} items", shown.size(), shown.size()));
    }

    @Override
    public void mergeFrom(Layer from) {
        // Merging is not supported
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        getItems().forEach(item -> v.visit(item.getBounds()));
    }

    @Override
    public Object getInfoComponent() {
        JPanel panel = new JPanel();
        StringBuilder text = new StringBuilder("<html>");
        if (source != null) {
            text.append("<b>").append(source.getName()).append("</b><br>")
                .append(source.getUrl()).append("<br><br>");
        }
        List<StacItem> shown = getItems();
        long[] range = dateRange(shown);
        text.append(trn("{0} item in the current view", "{0} items in the current view", shown.size(), shown.size()));
        int hidden = getHiddenCount();
        if (hidden > 0) {
            text.append("<br>").append(trn("{0} older item is covered by a newer one",
                    "{0} older items are covered by newer ones", hidden, hidden));
        }
        if (range[1] > 0) {
            text.append("<br>").append(tr("Dates: {0} to {1}", formatEpoch(range[0]), formatEpoch(range[1])));
        }
        if (source != null && !source.getAttribution().isEmpty()) {
            text.append("<br><br>").append(source.getAttribution());
        }
        text.append("</html>");
        panel.add(new JLabel(text.toString()));
        return panel;
    }

    @Override
    public Action[] getMenuEntries() {
        return new Action[] {
                LayerListDialog.getInstance().createShowHideLayerAction(),
                LayerListDialog.getInstance().createDeleteLayerAction(),
                SeparatorLayerAction.INSTANCE,
                new RefreshAction(),
                new ToggleLatestAction(),
                new ToggleLabelsAction(),
                SeparatorLayerAction.INSTANCE,
                new LayerListPopup.InfoAction(this)
        };
    }

    @Override
    public synchronized void destroy() {
        refreshTimer.stop();
        NavigatableComponent.removeZoomChangeListener(this);
        listeners.clear();
        super.destroy();
    }

    /**
     * Reloads the items of the current map view.
     */
    private class RefreshAction extends AbstractAction {
        RefreshAction() {
            super(tr("Reload dates"));
            new ImageProvider("dialogs", "refresh").getResource().attachImageIcon(this, true);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            refresh(true);
        }
    }

    /**
     * Switches between the most recent coverage and all items of the query.
     */
    private class ToggleLatestAction extends AbstractAction {
        ToggleLatestAction() {
            super(StacSettings.LATEST_ONLY.get() ? tr("Show all dates") : tr("Show only the most recent coverage"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            setLatestOnly(!StacSettings.LATEST_ONLY.get());
        }
    }

    /**
     * Switches the date labels in the map view on and off.
     */
    private class ToggleLabelsAction extends AbstractAction {
        ToggleLabelsAction() {
            super(StacSettings.SHOW_LABELS.get() ? tr("Hide date labels") : tr("Show date labels"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            StacSettings.SHOW_LABELS.put(!StacSettings.SHOW_LABELS.get());
            invalidate();
        }
    }
}
