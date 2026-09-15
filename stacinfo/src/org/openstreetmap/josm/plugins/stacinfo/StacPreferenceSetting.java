// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.preferences.SubPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.TabPreferenceSetting;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;

/**
 * Preferences of the plugin, shown as a tab of the imagery preferences.
 * <p>
 * The table lists the STAC endpoints; new ones can be added here or by editing the JSON file whose
 * location is shown below the table.
 */
public class StacPreferenceSetting implements SubPreferenceSetting {

    private final SourceTableModel model = new SourceTableModel();
    private final JTable table = new JTable(model);
    private final JCheckBox autoRefreshBox = new JCheckBox(tr("Reload the dates automatically when the map view changes"));
    private final JCheckBox latestOnlyBox = new JCheckBox(tr("Show only the most recent coverage, hide superseded items"));
    private final JCheckBox labelsBox = new JCheckBox(tr("Draw the date into every footprint"));
    private final JCheckBox legendBox = new JCheckBox(tr("Draw the colour legend into the map view"));
    private final JSpinner maxItemsSpinner = new JSpinner(new SpinnerNumberModel(500, 10, 10_000, 50));
    private final JSpinner maxAreaSpinner = new JSpinner(new SpinnerNumberModel(0.15, 0.001, 10.0, 0.05));

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        model.setSources(StacSources.get());

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(2).setMaxWidth(110);

        JPanel buttons = new JPanel(new GridBagLayout());
        buttons.add(button(tr("New"), e -> addSource()), GBC.eol().fill(GBC.HORIZONTAL));
        buttons.add(button(tr("Edit"), e -> editSource()), GBC.eol().fill(GBC.HORIZONTAL));
        buttons.add(button(tr("Duplicate"), e -> duplicateSource()), GBC.eol().fill(GBC.HORIZONTAL));
        buttons.add(button(tr("Remove"), e -> removeSource()), GBC.eol().fill(GBC.HORIZONTAL));
        buttons.add(button(tr("Reset"), e -> resetSources()), GBC.eol().fill(GBC.HORIZONTAL).insets(0, 10, 0, 0));
        buttons.add(new JLabel(), GBC.eol().fill(GBC.BOTH));

        JPanel tablePanel = new JPanel(new BorderLayout(5, 0));
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);
        tablePanel.add(buttons, BorderLayout.EAST);

        autoRefreshBox.setSelected(StacSettings.AUTO_REFRESH.get());
        latestOnlyBox.setSelected(StacSettings.LATEST_ONLY.get());
        labelsBox.setSelected(StacSettings.SHOW_LABELS.get());
        legendBox.setSelected(StacSettings.SHOW_LEGEND.get());
        maxItemsSpinner.setValue(StacSettings.MAX_ITEMS.get());
        maxAreaSpinner.setValue(StacSettings.MAX_AREA.get());

        JPanel options = new JPanel(new GridBagLayout());
        options.setBorder(BorderFactory.createTitledBorder(tr("Display")));
        options.add(autoRefreshBox, GBC.eol().insets(5, 2, 5, 2));
        options.add(latestOnlyBox, GBC.eol().insets(5, 2, 5, 2));
        options.add(labelsBox, GBC.eol().insets(5, 2, 5, 2));
        options.add(legendBox, GBC.eol().insets(5, 2, 5, 2));
        options.add(new JLabel(tr("Maximum number of items per query") + ": "), GBC.std().insets(5, 2, 5, 2));
        options.add(maxItemsSpinner, GBC.eol().insets(0, 2, 5, 2));
        options.add(new JLabel(tr("Maximum size of the queried map view (square degrees)") + ": "), GBC.std().insets(5, 2, 5, 2));
        options.add(maxAreaSpinner, GBC.eol().insets(0, 2, 5, 2));

        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel("<html>" + tr("STAC endpoints and WFS that are queried for the acquisition dates of "
                + "imagery layers. The column <i>Background layers</i> decides for which imagery layer a source is "
                + "offered automatically.") + "</html>"), GBC.eol().fill(GBC.HORIZONTAL).insets(5, 5, 5, 5));
        panel.add(tablePanel, GBC.eol().fill(GBC.BOTH).insets(5, 0, 5, 5));
        panel.add(options, GBC.eol().fill(GBC.HORIZONTAL).insets(5, 0, 5, 5));

        JPanel filePanel = new JPanel(new GridBagLayout());
        filePanel.add(new JLabel(tr("File") + ": " + StacSources.getUserFile()), GBC.std().insets(5, 2, 5, 5));
        filePanel.add(button(tr("Reload file"), e -> {
            StacSources.reload();
            model.setSources(StacSources.get());
        }), GBC.eol().insets(5, 2, 5, 5));
        panel.add(filePanel, GBC.eol().fill(GBC.HORIZONTAL));

        getTabPreferenceSetting(gui).addSubTab(this, tr("STAC dates"), panel,
                tr("Endpoints used to show how up to date the background imagery is"));
    }

    private static JButton button(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.addActionListener(listener);
        return button;
    }

    private void addSource() {
        edit(new StacSource("", ""), -1);
    }

    private void editSource() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            edit(model.getSource(row), row);
        }
    }

    private void duplicateSource() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            StacSource copy = model.getSource(row).copy();
            copy.setName(tr("{0} (copy)", copy.getName()));
            copy.setBundled(false);
            edit(copy, -1);
        }
    }

    private void edit(StacSource source, int row) {
        StacSourceEditor editor = new StacSourceEditor(table, source);
        editor.showDialog();
        if (editor.getValue() != 1) {
            return;
        }
        StacSource result = editor.getResult();
        if (result.getName().isEmpty() || result.getUrl().isEmpty()) {
            JOptionPane.showMessageDialog(table, tr("Name and URL are required."), tr("STAC source"),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (row >= 0) {
            model.setSource(row, result);
        } else {
            model.addSource(result);
        }
    }

    private void removeSource() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        StacSource source = model.getSource(row);
        if (source.isBundled()) {
            source.setEnabled(false);
            model.fireTableRowsUpdated(row, row);
            JOptionPane.showMessageDialog(table,
                    tr("''{0}'' is delivered with the plugin and has been switched off instead of being deleted.",
                            source.getName()),
                    tr("STAC source"), JOptionPane.INFORMATION_MESSAGE);
        } else {
            model.removeSource(row);
        }
    }

    private void resetSources() {
        model.setSources(StacSources.getBundled());
    }

    @Override
    public boolean ok() {
        StacSettings.AUTO_REFRESH.put(autoRefreshBox.isSelected());
        StacSettings.LATEST_ONLY.put(latestOnlyBox.isSelected());
        StacSettings.SHOW_LABELS.put(labelsBox.isSelected());
        StacSettings.SHOW_LEGEND.put(legendBox.isSelected());
        StacSettings.MAX_ITEMS.put(((Number) maxItemsSpinner.getValue()).intValue());
        StacSettings.MAX_AREA.put(((Number) maxAreaSpinner.getValue()).doubleValue());
        try {
            StacSources.save(model.getSources());
        } catch (IOException e) {
            Logging.error(e);
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    tr("Cannot save the STAC sources:") + "\n" + e.getMessage(),
                    tr("STAC source"), JOptionPane.ERROR_MESSAGE);
        }
        StacSources.reload();
        StacInfoPlugin plugin = StacInfoPlugin.getInstance();
        if (plugin != null && plugin.getDialog() != null) {
            plugin.getDialog().updateSources();
        }
        return false;
    }

    @Override
    public boolean isExpert() {
        return false;
    }

    @Override
    public TabPreferenceSetting getTabPreferenceSetting(PreferenceTabbedPane gui) {
        return gui.getImageryPreference();
    }

    /**
     * Table model of the configured sources.
     */
    private static final class SourceTableModel extends AbstractTableModel {

        private final transient List<StacSource> sources = new ArrayList<>();

        void setSources(List<StacSource> newSources) {
            sources.clear();
            newSources.forEach(source -> sources.add(source.copy()));
            fireTableDataChanged();
        }

        List<StacSource> getSources() {
            return sources;
        }

        StacSource getSource(int row) {
            return sources.get(row);
        }

        void setSource(int row, StacSource source) {
            sources.set(row, source);
            fireTableRowsUpdated(row, row);
        }

        void addSource(StacSource source) {
            sources.add(source);
            fireTableRowsInserted(sources.size() - 1, sources.size() - 1);
        }

        void removeSource(int row) {
            sources.remove(row);
            fireTableRowsDeleted(row, row);
        }

        @Override
        public int getRowCount() {
            return sources.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 0: return tr("Use");
                case 1: return tr("Name");
                case 2: return tr("Type");
                case 3: return tr("URL");
                default: return tr("Background layers");
            }
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            StacSource source = sources.get(row);
            switch (column) {
                case 0: return source.isEnabled();
                case 1: return source.getName();
                case 2: return source.getType().getLabel();
                case 3: return source.getUrl();
                default: return String.join(", ", source.getLayerMatch());
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && value instanceof Boolean) {
                sources.get(row).setEnabled((Boolean) value);
                fireTableRowsUpdated(row, row);
            }
        }
    }
}
