// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.datatransfer.ClipboardUtils;
import org.openstreetmap.josm.tools.OpenBrowser;

/**
 * Shows everything the STAC endpoint knows about one item: all properties and the files it offers.
 */
public class StacItemDialog extends ExtendedDialog {

    private final transient StacItem item;
    private final transient DetailTableModel model;

    /**
     * Creates the dialog.
     * @param parent the parent component
     * @param item the item to show
     */
    public StacItemDialog(Component parent, StacItem item) {
        super(parent, tr("STAC item"), tr("Close"), tr("Copy"));
        this.item = item;
        this.model = new DetailTableModel(item);
        setButtonIcons("ok", "copy");
        setContent(build(), false);
        setDefaultButton(1);
    }

    private JPanel build() {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openIfUrl(table);
                }
            }
        });

        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.add(new JLabel("<html><b>" + item.getDateLabel() + "</b> &nbsp; " + item.getId()
                + (item.getCollection().isEmpty() ? "" : " &nbsp; (" + item.getCollection() + ")") + "</html>"),
                BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(640, 360));
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(new JLabel(tr("Double click on a URL opens it in the browser.")), BorderLayout.SOUTH);
        return panel;
    }

    private void openIfUrl(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        String value = String.valueOf(model.getValueAt(table.convertRowIndexToModel(row), 1));
        if (value.startsWith("http://") || value.startsWith("https://")) {
            OpenBrowser.displayUrl(value);
        }
    }

    @Override
    protected void buttonAction(int buttonIndex, ActionEvent evt) {
        if (buttonIndex == 1) {
            ClipboardUtils.copyString(model.asText());
        } else {
            super.buttonAction(buttonIndex, evt);
        }
    }

    /**
     * Returns the properties and assets of an item as a two column table.
     */
    private static final class DetailTableModel extends AbstractTableModel {

        private final transient List<String[]> rows = new ArrayList<>();

        DetailTableModel(StacItem item) {
            rows.add(new String[] {"id", item.getId()});
            if (!item.getCollection().isEmpty()) {
                rows.add(new String[] {"collection", item.getCollection()});
            }
            for (Map.Entry<String, String> property : item.getProperties().entrySet()) {
                rows.add(new String[] {property.getKey(), property.getValue()});
            }
            for (Map.Entry<String, String> asset : item.getAssets().entrySet()) {
                rows.add(new String[] {tr("Asset") + ": " + asset.getKey(), asset.getValue()});
            }
            rows.add(new String[] {"bbox", item.getBounds().toString()});
        }

        String asText() {
            StringBuilder text = new StringBuilder();
            rows.forEach(row -> text.append(row[0]).append('\t').append(row[1]).append('\n'));
            return text.toString();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? tr("Property") : tr("Value");
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows.get(rowIndex)[columnIndex];
        }
    }
}
