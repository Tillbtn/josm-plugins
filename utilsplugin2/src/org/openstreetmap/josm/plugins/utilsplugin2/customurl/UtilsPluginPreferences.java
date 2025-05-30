// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.utilsplugin2.customurl;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.gui.widgets.HistoryComboBox;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.tools.GBC;

/**
 * Preferences of Utilsplugin2 functions
 */
public class UtilsPluginPreferences extends DefaultTabPreferenceSetting {
    HistoryComboBox combo1 = new HistoryComboBox();
    JTable table;
    JButton resetButton;
    JButton loadButton;
    JButton saveButton;

    public UtilsPluginPreferences() {
        super("utils", tr("Utilsplugin2 settings"), tr("Here you can change some preferences of Utilsplugin2 functions"));
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        JPanel all = new JPanel(new GridBagLayout());

        URLList.getSelectedURL();
        table = new JTable(new DefaultTableModel(null, new String[]{"Title", "URL"}));

        List<String> items = URLList.getURLList();

        resetButton = new JButton(tr("Reset"));
        resetButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                fillRows(URLList.resetURLList());
            }
        });

        saveButton = new JButton(tr("Save to file"));
        saveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                URLList.saveURLList(readItemsFromTable());
            }
        });

        loadButton = new JButton(tr("Load from file"));
        loadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                fillRows(URLList.loadURLList());
            }
        });

        fillRows(items);

        HtmlPanel help = new HtmlPanel(tr("Please edit custom URLs and select one row to use with the tool<br/>"
                + " <b>&#123;key&#125;</b> is replaced with the tag value<br/>"
                + " <b>&#123;#id&#125;</b> is replaced with the element ID<br/>"
                + " <b>&#123;#type&#125;</b> is replaced with \"node\",\"way\" or \"relation\" <br/>"
                + " <b>&#123;#lat&#125; , &#123;#lon&#125;</b> is replaced with map center latitude/longitude <br/>"
                + " Your can manually load settings from file <b>customurl.txt</b> in JOSM folder"));

        all.add(new JLabel(tr("Custom URL configuration")), GBC.std().insets(5, 10, 0, 0));
        all.add(resetButton, GBC.std().insets(25, 10, 0, 0));
        all.add(loadButton, GBC.std().insets(25, 10, 0, 0));
        all.add(saveButton, GBC.eol().insets(25, 10, 0, 0));
        all.add(help, GBC.eop().insets(5, 10, 0, 0));

        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(0).setMaxWidth(300);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getModel().addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                int row = e.getFirstRow();
                int column = e.getColumn();
                DefaultTableModel model = (DefaultTableModel) e.getSource();
                if (row < 0 || column < 0) return;
                String data = (String) model.getValueAt(row, column);
                if (data != null && data.length() > 0 && row == model.getRowCount()-1)
                    model.addRow(new String[]{"", ""});
            }
        });
        all.add(table, GBC.eop().fill());
        createPreferenceTabWithScrollPane(gui, all);
    }

    private void fillRows(List<String> items) {
        if (items == null) return;
        int p = 0;
        String name, url;
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        model.setRowCount(0);
        int n = items.size();
        while (true) {
            if (p >= n) break;
            name = items.get(p);
            p++;
            if (p >= n) break;
            url = items.get(p);
            p++;
            model.addRow(new String[]{name, url});
        }
        model.addRow(new String[]{"", ""});
    }

    @Override
    public boolean ok() {
        combo1.getText();
        List<String> lst = readItemsFromTable();
        URLList.updateURLList(lst);

        return false;
    }

    private List<String> readItemsFromTable() {
        TableModel model = table.getModel();
        String v;
        ArrayList<String> lst = new ArrayList<>();
        int n = model.getRowCount();
        for (int i = 0; i < n; i++) {
            v = (String) model.getValueAt(i, 0);
            if (v.length() == 0) continue;
            lst.add(v);
            v = (String) model.getValueAt(i, 1);
            lst.add(v);
        }
        int row = table.getSelectedRow();
        if (row != -1) {
            v = (String) model.getValueAt(row, 1);
            URLList.select(v);
        }
        return lst;
    }
}
