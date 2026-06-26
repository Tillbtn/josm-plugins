// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.bicycleroad;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.border.Border;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.plugins.bicycleroad.BicycleRoadTags.AdditionalSign;
import org.openstreetmap.josm.plugins.bicycleroad.BicycleRoadTags.RoadType;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Tags the selected way(s) as a bicycle road.
 * <p>
 * Opens a small dialog to pick the road type ("Fahrradstraße" /
 * "Fahrradzone") and an optional additional sign ("Anlieger frei" / "KFZ
 * frei"). The last choice is remembered in the JOSM preferences and pre-selected
 * the next time, and the OK button is the default — so the common workflow
 * (select way → trigger → press Enter) re-applies the previous combination in
 * one undoable step.
 * <p>
 * The actual key/value pairs live in {@link BicycleRoadTags}.
 */
public class BicycleRoadAction extends JosmAction {

    /** Preference key storing the last-used {@link RoadType} (by {@code name()}). */
    static final String PREF_TYPE = "bicycleroad.type";
    /** Preference key storing the last-used {@link AdditionalSign} (by {@code name()}). */
    static final String PREF_SIGN = "bicycleroad.sign";

    /**
     * Constructs a new {@code BicycleRoadAction}.
     * <p>
     * The shortcut is registered without a default key binding to avoid clashing
     * with core/other plugins; users can assign one in Preferences &gt; Shortcuts.
     */
    public BicycleRoadAction() {
        super(tr("Tag bicycle road"), "fahrradstrasse",
                tr("Tag the selected way(s) as a Fahrradstraße/Fahrradzone, with an optional additional sign."),
                Shortcut.registerShortcut("data:bicycleroad", tr("Data: {0}", tr("Tag bicycle road")),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) {
            return;
        }
        Collection<OsmPrimitive> selection = ds.getSelected();
        if (selection.isEmpty()) {
            new Notification(tr("Please select at least one object first."))
                    .setIcon(JOptionPane.WARNING_MESSAGE).show();
            return;
        }

        // Restore the previous choice (defaults on first run / bad pref values).
        RoadType type = readEnum(PREF_TYPE, RoadType.class, RoadType.FAHRRADSTRASSE);
        AdditionalSign sign = readEnum(PREF_SIGN, AdditionalSign.class, AdditionalSign.NONE);

        JPanel panel = new JPanel(new GridBagLayout());

        panel.add(new JLabel("<html><b>" + tr("Type") + "</b></html>"), GBC.eol().insets(0, 0, 0, 4));
        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ButtonGroup typeGroup = new ButtonGroup();
        Map<RoadType, JRadioButton> typeButtons = new LinkedHashMap<>();
        for (RoadType t : RoadType.values()) {
            JRadioButton rb = createOption(t.getLabel(), t.getImageName(), t == type, typeGroup);
            typeButtons.put(t, rb);
            typeRow.add(rb);
        }
        panel.add(typeRow, GBC.eol());

        panel.add(new JLabel("<html><b>" + tr("Additional sign (optional)") + "</b></html>"),
                GBC.eol().insets(0, 12, 0, 4));
        JPanel signRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ButtonGroup signGroup = new ButtonGroup();
        Map<AdditionalSign, JRadioButton> signButtons = new LinkedHashMap<>();
        for (AdditionalSign s : AdditionalSign.values()) {
            JRadioButton rb = createOption(s.getLabel(), s.getImageName(), s == sign, signGroup);
            signButtons.put(s, rb);
            signRow.add(rb);
        }
        panel.add(signRow, GBC.eol());

        ExtendedDialog dialog = new ExtendedDialog(MainApplication.getMainFrame(),
                tr("Tag bicycle road"), tr("Apply"), tr("Cancel"));
        dialog.setButtonIcons("ok", "cancel");
        dialog.setIcon(JOptionPane.QUESTION_MESSAGE);
        dialog.setContent(panel);
        dialog.setDefaultButton(1);
        dialog.showDialog();

        if (dialog.getValue() != 1) {
            return;
        }

        // Read back the user's choice.
        type = selectedKey(typeButtons, RoadType.FAHRRADSTRASSE);
        sign = selectedKey(signButtons, AdditionalSign.NONE);

        // Persist for next time.
        Config.getPref().put(PREF_TYPE, type.name());
        Config.getPref().put(PREF_SIGN, sign.name());

        Map<String, String> tags = BicycleRoadTags.buildTags(type, sign);

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(ds, selection, tags));
        new Notification(trn("Tagged {0} object as {1}.", "Tagged {0} objects as {1}.",
                selection.size(), selection.size(), describe(type, sign)))
                .setIcon(JOptionPane.INFORMATION_MESSAGE).show();
    }

    /** Max height (px) of the sign images shown in the dialog. */
    private static final int SIGN_HEIGHT = 28;
    /** Highlight colour of the border around the selected option. */
    private static final Color SELECTED_COLOR = new Color(0x2D7DD2);
    /** Border drawn around the currently selected option. */
    private static final Border SELECTED_BORDER = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(SELECTED_COLOR, 2),
            BorderFactory.createEmptyBorder(3, 5, 3, 5));
    /** Border drawn around unselected options (same footprint, so nothing jumps). */
    private static final Border UNSELECTED_BORDER = BorderFactory.createEmptyBorder(5, 7, 5, 7);

    /**
     * Builds one selectable option: a radio button showing the sign image above
     * its label. Because the custom image replaces the native radio indicator,
     * selection is shown with a coloured border that updates as the choice
     * changes within the group.
     *
     * @param label     the option label
     * @param imageName icon name resolved from the jar's {@code images/}, or {@code null} for no image
     * @param selected  whether this option starts selected
     * @param group     the button group the option belongs to
     * @return the configured radio button
     */
    private static JRadioButton createOption(String label, String imageName, boolean selected, ButtonGroup group) {
        JRadioButton button = new JRadioButton(label, selected);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        if (imageName != null) {
            ImageIcon icon = new ImageProvider(imageName).setMaxHeight(SIGN_HEIGHT).setOptional(true).get();
            if (icon != null) {
                button.setIcon(icon);
                button.setIconTextGap(6);
            }
        }
        group.add(button);
        button.addItemListener(e -> updateSelectionStyle(button));
        updateSelectionStyle(button);
        return button;
    }

    /** Applies the selected/unselected border to {@code button} based on its state. */
    private static void updateSelectionStyle(AbstractButton button) {
        button.setBorder(button.isSelected() ? SELECTED_BORDER : UNSELECTED_BORDER);
        button.setBorderPainted(true);
    }

    /** Builds a short human-readable summary of the applied choice for the toast. */
    private static String describe(RoadType type, AdditionalSign sign) {
        if (sign == AdditionalSign.NONE) {
            return type.getLabel();
        }
        return type.getLabel() + " + " + sign.getLabel();
    }

    /** @return the enum constant whose radio button is selected, or {@code fallback}. */
    private static <E extends Enum<E>> E selectedKey(Map<E, JRadioButton> buttons, E fallback) {
        for (Map.Entry<E, JRadioButton> entry : buttons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return fallback;
    }

    /** Reads an enum constant stored by {@code name()} in the preferences, tolerating bad values. */
    private static <E extends Enum<E>> E readEnum(String prefKey, Class<E> type, E fallback) {
        String stored = Config.getPref().get(prefKey, fallback.name());
        try {
            return Enum.valueOf(type, stored);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    @Override
    protected void updateEnabledState() {
        DataSet ds = getLayerManager().getEditDataSet();
        setEnabled(ds != null && !ds.getSelected().isEmpty());
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        setEnabled(selection != null && !selection.isEmpty());
    }
}
