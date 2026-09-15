// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;

import org.openstreetmap.josm.actions.PreferencesAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Dialog showing the acquisition dates of the imagery in the current map view.
 */
public class StacInfoDialog extends ToggleDialog implements StacLayer.StacLayerListener, LayerManager.LayerChangeListener {

    private static final String AUTOMATIC = " automatic";

    private final JComboBox<Object> sourceCombo = new JComboBox<>();
    private final JCheckBox autoRefreshBox = new JCheckBox(tr("Automatically"));
    private final JCheckBox latestOnlyBox = new JCheckBox(tr("Latest only"));
    private final StacItemTableModel model = new StacItemTableModel();
    private final JTable table = new ItemTable();
    private final JosmTextField filterField = new JosmTextField();
    private final JLabel summary = new JLabel(" ");
    private final transient RefreshAction refreshAction = new RefreshAction();
    private final transient ZoomAction zoomAction = new ZoomAction();
    private final transient DetailsAction detailsAction = new DetailsAction();
    private final Timer filterTimer = new Timer(300, e -> applyFilter());
    private transient StacLayer layer;
    private boolean updating;

    /**
     * Creates the dialog.
     */
    public StacInfoDialog() {
        super(tr("Imagery dates (STAC)"), "stacinfo", tr("Shows how up to date the background imagery is"),
                Shortcut.registerShortcut("subwindow:stacinfo", tr("Toggle: {0}", tr("Imagery dates (STAC)")),
                        KeyEvent.VK_Y, Shortcut.NONE),
                180);
        build();
        updateSources();
    }

    private void build() {
        sourceCombo.setRenderer(new SourceRenderer());
        sourceCombo.addActionListener(e -> sourceSelected());
        autoRefreshBox.setSelected(StacSettings.AUTO_REFRESH.get());
        autoRefreshBox.setToolTipText(tr("Reload the dates whenever the map view changes"));
        autoRefreshBox.addActionListener(e -> StacSettings.AUTO_REFRESH.put(autoRefreshBox.isSelected()));
        latestOnlyBox.setSelected(StacSettings.LATEST_ONLY.get());
        latestOnlyBox.setToolTipText(
                tr("Show only the most recent image of every place instead of the whole archive of the endpoint"));
        latestOnlyBox.addActionListener(e -> latestOnlyChanged());

        filterTimer.setRepeats(false);
        filterField.setHint(tr("Filter, for example dop10 or bodenpixelgroesse=20"));
        filterField.setToolTipText("<html>" + tr("Shows only the items matching all parts of the filter.<br>"
                + "{0} searches id, date and all properties, {1} searches one property, "
                + "a leading {2} excludes the matching items.", "<i>dop10</i>", "<i>bodenpixelgroesse=20</i>",
                "<i>-</i>") + "</html>");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterTimer.restart();
            }
        });

        JPanel top = new JPanel(new GridBagLayout());
        top.add(new JLabel(tr("Source") + ": "), GBC.std().insets(5, 2, 2, 2));
        top.add(sourceCombo, GBC.std().fill(GBC.HORIZONTAL).insets(0, 2, 2, 2));
        top.add(autoRefreshBox, GBC.eol().insets(0, 2, 5, 2));
        top.add(latestOnlyBox, GBC.std().insets(5, 0, 5, 2));
        top.add(filterField, GBC.eol().fill(GBC.HORIZONTAL).insets(5, 0, 5, 2));

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(this::selectionChanged);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    zoomAction.actionPerformed(null);
                }
            }
        });

        summary.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(summary, BorderLayout.SOUTH);

        createLayout(content, false, Arrays.asList(
                new SideButton(refreshAction),
                new SideButton(zoomAction),
                new SideButton(detailsAction),
                new SideButton(PreferencesAction.forPreferenceSubTab(tr("Sources"),
                        tr("Configure the STAC endpoints"), StacPreferenceSetting.class))));
    }

    /**
     * Rebuilds the list of selectable sources.
     */
    public void updateSources() {
        updating = true;
        try {
            Object selected = sourceCombo.getSelectedItem();
            sourceCombo.removeAllItems();
            sourceCombo.addItem(AUTOMATIC);
            for (StacSource source : StacSources.getEnabled()) {
                sourceCombo.addItem(source);
            }
            if (selected instanceof StacSource && StacSources.getEnabled().contains(selected)) {
                sourceCombo.setSelectedItem(selected);
            } else {
                sourceCombo.setSelectedIndex(0);
            }
        } finally {
            updating = false;
        }
        updateStatus();
    }

    /**
     * Returns the source the user has selected, resolving the automatic entry against the background layers.
     * @return the selected source, or {@code null} if no source matches the loaded imagery layers
     */
    public StacSource getSelectedSource() {
        Object selected = sourceCombo.getSelectedItem();
        if (selected instanceof StacSource) {
            return (StacSource) selected;
        }
        List<StacSource> matching = StacSources.forActiveImageryLayers();
        return matching.isEmpty() ? null : matching.get(0);
    }

    private void sourceSelected() {
        if (updating) {
            return;
        }
        StacSource source = getSelectedSource();
        if (source != null && layer != null) {
            layer.setSource(source);
        }
        updateStatus();
    }

    private void applyFilter() {
        if (layer != null) {
            layer.setFilter(StacFilter.parse(filterField.getText()));
        }
        updateStatus();
    }

    private void latestOnlyChanged() {
        if (layer != null) {
            layer.setLatestOnly(latestOnlyBox.isSelected());
        } else {
            StacSettings.LATEST_ONLY.put(latestOnlyBox.isSelected());
        }
        updateStatus();
    }

    private void selectionChanged(ListSelectionEvent event) {
        if (event.getValueIsAdjusting()) {
            return;
        }
        detailsAction.setEnabled(table.getSelectedRowCount() == 1);
        if (layer == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            ids.add(model.getItem(table.convertRowIndexToModel(row)).getId());
        }
        layer.setHighlighted(ids);
    }

    /**
     * Returns the layer showing the dates, creating it if necessary.
     * @return the layer, or {@code null} if no source is available
     */
    public StacLayer getOrCreateLayer() {
        StacSource source = getSelectedSource();
        if (source == null) {
            return null;
        }
        StacLayer existing = StacInfoPlugin.findLayer();
        if (existing == null) {
            existing = new StacLayer(source);
            existing.setFilter(StacFilter.parse(filterField.getText()));
            MainApplication.getLayerManager().addLayer(existing);
        } else {
            existing.setSource(source);
        }
        return existing;
    }

    @Override
    public void stacLayerChanged(StacLayer changedLayer) {
        GuiHelper.runInEDT(this::updateStatus);
    }

    private void updateStatus() {
        StacLayer current = layer;
        latestOnlyBox.setSelected(StacSettings.LATEST_ONLY.get());
        List<StacItem> items = current == null ? Collections.emptyList() : current.getItems();
        model.setContent(items, current == null ? getSelectedSource() : current.getSource());
        zoomAction.setEnabled(!items.isEmpty());
        if (current == null) {
            StacSource source = getSelectedSource();
            summary.setText(source == null
                    ? tr("No STAC source configured for the loaded background layers")
                    : tr("Press the reload button to show the dates of ''{0}''", source.getName()));
            return;
        }
        String text = trn("{0} item", "{0} items", items.size(), items.size()) + " - " + dateRangeText(items);
        int hidden = current.getHiddenCount();
        if (hidden > 0) {
            text += " - " + tr("{0} superseded hidden", hidden);
        }
        int filtered = current.getFilteredOutCount();
        if (filtered > 0) {
            text += " - " + tr("{0} filtered out", filtered);
        }
        if (!current.getStatus().isEmpty()) {
            text = current.getStatus() + (items.isEmpty() ? "" : " - " + text);
        }
        summary.setText(text);
    }

    private static String dateRangeText(List<StacItem> items) {
        Instant oldest = null;
        Instant newest = null;
        for (StacItem item : items) {
            Instant date = item.getDate();
            if (date == null) {
                continue;
            }
            if (oldest == null || date.isBefore(oldest)) {
                oldest = date;
            }
            if (newest == null || date.isAfter(newest)) {
                newest = date;
            }
        }
        if (newest == null) {
            return tr("no dates");
        }
        String newestText = newest.toString().substring(0, 10);
        if (newest.equals(oldest)) {
            return tr("date: {0}", newestText);
        }
        return tr("dates: {0} to {1}", oldest.toString().substring(0, 10), newestText);
    }

    private void setLayer(StacLayer newLayer) {
        if (layer == newLayer) {
            return;
        }
        if (layer != null) {
            layer.removeStacLayerListener(this);
        }
        layer = newLayer;
        if (layer != null) {
            layer.addStacLayerListener(this);
            filterField.setText(layer.getFilter().getText());
            if (!(sourceCombo.getSelectedItem() instanceof StacSource)) {
                updating = true;
                sourceCombo.setSelectedItem(layer.getSource());
                updating = false;
            }
        }
        updateStatus();
    }

    @Override
    public void showNotify() {
        MainApplication.getLayerManager().addLayerChangeListener(this);
        setLayer(StacInfoPlugin.findLayer());
        updateSources();
    }

    @Override
    public void hideNotify() {
        MainApplication.getLayerManager().removeLayerChangeListener(this);
        setLayer(null);
    }

    @Override
    public void layerAdded(LayerManager.LayerAddEvent e) {
        if (e.getAddedLayer() instanceof StacLayer) {
            setLayer((StacLayer) e.getAddedLayer());
        } else {
            updateSources();
        }
    }

    @Override
    public void layerRemoving(LayerManager.LayerRemoveEvent e) {
        Layer removed = e.getRemovedLayer();
        if (removed == layer) {
            setLayer(null);
        } else if (!(removed instanceof StacLayer)) {
            updateSources();
        }
    }

    @Override
    public void layerOrderChanged(LayerManager.LayerOrderChangeEvent e) {
        // Nothing to do
    }

    /**
     * Table of the items, showing all properties of a row as tooltip.
     */
    private final class ItemTable extends JTable {
        ItemTable() {
            super(model);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row < 0) {
                return null;
            }
            StacItem item = model.getItem(convertRowIndexToModel(row));
            StringBuilder text = new StringBuilder("<html><b>").append(item.getDateLabel()).append("</b><br>")
                    .append(item.getId());
            item.getProperties().forEach((key, value) -> text.append("<br>").append(key).append(": ").append(value));
            return text.append("</html>").toString();
        }
    }

    /**
     * Shows all properties and files of the selected item.
     */
    private class DetailsAction extends AbstractAction {
        DetailsAction() {
            super(tr("Details"));
            new ImageProvider("info").getResource().attachImageIcon(this, true);
            putValue(SHORT_DESCRIPTION, tr("Show all properties of the selected item"));
            setEnabled(false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            int row = table.getSelectedRow();
            if (row >= 0) {
                new StacItemDialog(table, model.getItem(table.convertRowIndexToModel(row))).showDialog();
            }
        }
    }

    /**
     * Renders the automatic entry of the source combo box.
     */
    private static final class SourceRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            Object shown = value;
            if (AUTOMATIC.equals(value)) {
                List<StacSource> matching = StacSources.forActiveImageryLayers();
                shown = matching.isEmpty()
                        ? tr("Automatic (no matching background layer)")
                        : tr("Automatic: {0}", matching.get(0).getName());
            }
            return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
        }
    }

    /**
     * Loads the items of the current map view.
     */
    private class RefreshAction extends AbstractAction {
        RefreshAction() {
            super(tr("Reload"));
            new ImageProvider("dialogs", "refresh").getResource().attachImageIcon(this, true);
            putValue(SHORT_DESCRIPTION, tr("Query the STAC API for the current map view"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            StacLayer current = getOrCreateLayer();
            if (current != null) {
                setLayer(current);
                current.refresh(true);
            } else {
                updateStatus();
            }
        }
    }

    /**
     * Zooms to the selected items.
     */
    private class ZoomAction extends AbstractAction {
        ZoomAction() {
            super(tr("Zoom to"));
            new ImageProvider("dialogs/autoscale", "selection").getResource().attachImageIcon(this, true);
            putValue(SHORT_DESCRIPTION, tr("Zoom to the selected items"));
            setEnabled(false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            if (!MainApplication.isDisplayingMapView()) {
                return;
            }
            List<StacItem> items = new ArrayList<>();
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) {
                if (layer != null) {
                    items.addAll(layer.getItems());
                }
            } else {
                for (int row : rows) {
                    items.add(model.getItem(table.convertRowIndexToModel(row)));
                }
            }
            Bounds bounds = null;
            for (StacItem item : items) {
                if (bounds == null) {
                    bounds = new Bounds(item.getBounds());
                } else {
                    bounds.extend(item.getBounds());
                }
            }
            if (bounds != null) {
                MainApplication.getMap().mapView.zoomTo(bounds);
            }
        }
    }
}
