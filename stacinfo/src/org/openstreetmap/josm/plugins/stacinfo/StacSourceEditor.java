// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.tools.ColorHelper;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

/**
 * Dialog to add or change one STAC or WFS endpoint.
 */
public class StacSourceEditor extends ExtendedDialog {

    private final JosmTextField nameField = new JosmTextField(30);
    private final JComboBox<StacSource.Type> typeBox = new JComboBox<>(StacSource.Type.values());
    private final JosmTextField urlField = new JosmTextField(30);
    private final JosmTextField collectionsField = new JosmTextField(30);
    private final JosmTextField layerMatchField = new JosmTextField(30);
    private final JosmTextField detailsField = new JosmTextField(30);
    private final JosmTextField dateField = new JosmTextField(30);
    private final JosmTextField colorField = new JosmTextField(30);
    private final JosmTextField attributionField = new JosmTextField(30);
    private final JosmTextField queryField = new JosmTextField(30);
    private final JLabel collectionsLabel = new JLabel();
    private final JCheckBox enabledBox = new JCheckBox(tr("Use this source"));
    private final JCheckBox sortBox = new JCheckBox(tr("Ask the server for the newest items first"));
    private final transient StacSource source;

    /**
     * Creates the editor.
     * @param parent the parent component
     * @param source the source to edit, its values are not changed before {@link #getResult()} is called
     */
    public StacSourceEditor(Component parent, StacSource source) {
        super(parent, source.getName().isEmpty() ? tr("New date source") : tr("Edit date source"),
                tr("OK"), tr("Cancel"));
        this.source = source;
        setButtonIcons("ok", "cancel");
        setContent(build(), false);
    }

    private JPanel build() {
        nameField.setText(source.getName());
        typeBox.setSelectedItem(source.getType());
        typeBox.setRenderer(new TypeRenderer());
        typeBox.setToolTipText(tr("Whether the dates are read from a STAC API or from the GetFeature operation of a WFS"));
        typeBox.addActionListener(e -> updateHints());
        urlField.setText(source.getUrl());
        collectionsField.setText(String.join(", ", source.getCollections()));
        layerMatchField.setText(String.join(", ", source.getLayerMatch()));
        layerMatchField.setToolTipText(
                tr("Comma separated regular expressions. The source is offered automatically if one of them "
                        + "occurs in name, id or URL of a background layer, for example Niedersachsen-DOP20"));
        detailsField.setText(String.join(", ", source.getDetailProperties()));
        detailsField.setToolTipText(tr("Item properties shown as additional table columns, comma separated"));
        dateField.setText(source.getDateProperty());
        colorField.setText(source.getColor() == null ? "" : ColorHelper.color2html(source.getColor()));
        colorField.setToolTipText(tr("Colour of the footprints as hexadecimal value, for example #E8590C"));
        attributionField.setText(source.getAttribution());
        queryField.setText(source.getQuery() == null ? "" : source.getQuery().toString());
        queryField.setToolTipText("<html>" + tr("Server side filter in the syntax of the STAC query extension, "
                + "for example {0} for the 20 cm images of the DOP collection or {1} for nearly cloudless scenes.",
                "<i>{\"bodenpixelgroesse\": \"20\"}</i>", "<i>{\"eo:cloud_cover\": {\"lt\": 10}}</i>") + "</html>");
        enabledBox.setSelected(source.isEnabled());
        sortBox.setSelected(source.isSortByDate());
        updateHints();

        JButton testButton = new JButton(tr("Test connection"));
        testButton.addActionListener(e -> testConnection());

        JPanel panel = new JPanel(new GridBagLayout());
        addRow(panel, tr("Name"), nameField);
        panel.add(new JLabel(tr("Type") + ": "), GBC.std().insets(5, 5, 5, 0));
        panel.add(typeBox, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 5, 5, 0));
        addRow(panel, tr("URL"), urlField);
        addRow(panel, collectionsLabel, collectionsField);
        addRow(panel, tr("Background layers"), layerMatchField);
        addRow(panel, tr("Detail properties"), detailsField);
        addRow(panel, tr("Date property"), dateField);
        addRow(panel, tr("Colour"), colorField);
        addRow(panel, tr("Attribution"), attributionField);
        addRow(panel, tr("Item filter"), queryField);
        panel.add(sortBox, GBC.eol().insets(5, 5, 5, 0));
        panel.add(enabledBox, GBC.eol().insets(5, 0, 5, 0));
        panel.add(testButton, GBC.eol().insets(5, 10, 5, 5));
        return panel;
    }

    private static void addRow(JPanel panel, JLabel label, JTextField field) {
        panel.add(label, GBC.std().insets(5, 5, 5, 0));
        panel.add(field, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 5, 5, 0));
    }

    private static void addRow(JPanel panel, String label, JTextField field) {
        addRow(panel, new JLabel(label + ": "), field);
    }

    /**
     * Adjusts the labels and tooltips that mean something different for the two source types.
     */
    private void updateHints() {
        if (StacSource.Type.WFS.equals(typeBox.getSelectedItem())) {
            collectionsLabel.setText(tr("Feature types") + ": ");
            collectionsField.setToolTipText(tr("Feature types to query, comma separated, for example app:dop_single. "
                    + "At least one is needed; every one of them is requested on its own."));
            urlField.setToolTipText(tr("URL of the WFS, for example https://isk.geobasis-bb.de/ows/aktualitaeten_wfs"));
            dateField.setToolTipText(tr("Feature property holding the acquisition date, for example creationdate"));
            sortBox.setToolTipText(tr("Adds SORTBY=<date property> D to the request. Switched off automatically "
                    + "if the service rejects it."));
        } else {
            collectionsLabel.setText(tr("Collections") + ": ");
            collectionsField.setToolTipText(tr("Collections to query, comma separated. Empty means all collections."));
            urlField.setToolTipText(tr("Root URL of the STAC API, for example https://dop.stac.lgln.niedersachsen.de/"));
            dateField.setToolTipText(tr("Item property holding the acquisition date, usually datetime"));
            sortBox.setToolTipText(tr("Adds sortby=-properties.datetime to the query. Switched off automatically "
                    + "if the endpoint rejects it."));
        }
        queryField.setEnabled(!StacSource.Type.WFS.equals(typeBox.getSelectedItem()));
    }

    private void testConnection() {
        StacSource test = getResult();
        if (test.getUrl().isEmpty()) {
            return;
        }
        MainApplication.worker.submit(() -> {
            String message;
            try {
                List<String[]> collections = StacClient.collections(test);
                if (collections.isEmpty()) {
                    message = test.isWfs()
                            ? tr("The service answered, but does not offer any feature type.")
                            : tr("The endpoint answered, but does not offer any collection.");
                } else {
                    message = (test.isWfs()
                            ? tr("The service offers {0} feature type(s):", collections.size())
                            : tr("The endpoint offers {0} collection(s):", collections.size())) + "\n"
                            + collections.stream().map(entry -> entry[0] + (entry[1].isEmpty() ? "" : " - " + entry[1]))
                                    .collect(Collectors.joining("\n"));
                }
            } catch (IOException | RuntimeException ex) {
                Logging.warn(ex);
                message = tr("The endpoint cannot be read:") + "\n" + ex.getMessage();
            }
            final String result = message;
            GuiHelper.runInEDT(() -> JOptionPane.showMessageDialog(this, result, tr("Test connection"),
                    JOptionPane.INFORMATION_MESSAGE));
        });
    }

    /**
     * Returns a source with the values entered by the user.
     * @return the edited copy of the source
     */
    public StacSource getResult() {
        StacSource result = source.copy();
        result.setName(nameField.getText());
        result.setType((StacSource.Type) typeBox.getSelectedItem());
        result.setUrl(urlField.getText());
        result.setCollections(split(collectionsField.getText()));
        result.setLayerMatch(split(layerMatchField.getText()));
        result.setDetailProperties(split(detailsField.getText()));
        result.setDateProperty(dateField.getText());
        result.setAttribution(attributionField.getText());
        result.setEnabled(enabledBox.isSelected());
        result.setSortByDate(sortBox.isSelected());
        result.setQuery(parseQuery(queryField.getText()));
        String color = colorField.getText().trim();
        result.setColor(color.isEmpty() ? null : ColorHelper.html2color(color));
        return result;
    }

    private JsonObject parseQuery(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try (JsonReader reader = Json.createReader(new StringReader(value))) {
            return reader.readObject();
        } catch (JsonException | IllegalStateException | ClassCastException e) {
            Logging.warn(e);
            JOptionPane.showMessageDialog(this, tr("The item filter is not a valid JSON object:") + "\n" + value,
                    tr("STAC source"), JOptionPane.WARNING_MESSAGE);
            return source.getQuery();
        }
    }

    private static List<String> split(String text) {
        return Arrays.stream(text.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Shows the translated label of a source type instead of the name of the enumeration constant.
     */
    private static class TypeRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            Object label = value instanceof StacSource.Type ? ((StacSource.Type) value).getLabel() : value;
            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus);
        }
    }
}
