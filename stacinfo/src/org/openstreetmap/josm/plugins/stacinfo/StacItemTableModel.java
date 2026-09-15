// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.table.AbstractTableModel;

/**
 * Table model listing the STAC items of the current map view.
 * <p>
 * The first column always shows the date, the last one the item id; in between one column per
 * property named in {@code detailProperties} of the source is shown.
 */
public class StacItemTableModel extends AbstractTableModel {

    private transient List<StacItem> items = Collections.emptyList();
    private List<String> detailColumns = Collections.emptyList();

    /**
     * Replaces the content of the table.
     * @param newItems the items to show
     * @param source the source they belong to, used for the detail columns, may be {@code null}
     */
    public void setContent(List<StacItem> newItems, StacSource source) {
        items = newItems == null ? Collections.emptyList() : new ArrayList<>(newItems);
        List<String> columns = source == null ? Collections.emptyList() : source.getDetailProperties();
        boolean structureChanged = !columns.equals(detailColumns);
        detailColumns = new ArrayList<>(columns);
        if (structureChanged) {
            fireTableStructureChanged();
        } else {
            fireTableDataChanged();
        }
    }

    /**
     * Returns the item of one row.
     * @param row the row index
     * @return the item shown in that row
     */
    public StacItem getItem(int row) {
        return items.get(row);
    }

    @Override
    public int getRowCount() {
        return items.size();
    }

    @Override
    public int getColumnCount() {
        return detailColumns.size() + 2;
    }

    @Override
    public String getColumnName(int column) {
        if (column == 0) {
            return tr("Date");
        }
        if (column <= detailColumns.size()) {
            return detailColumns.get(column - 1);
        }
        return tr("Item");
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        StacItem item = items.get(rowIndex);
        if (columnIndex == 0) {
            return item.getDateLabel();
        }
        if (columnIndex <= detailColumns.size()) {
            return item.getProperty(detailColumns.get(columnIndex - 1));
        }
        return item.getId();
    }
}
