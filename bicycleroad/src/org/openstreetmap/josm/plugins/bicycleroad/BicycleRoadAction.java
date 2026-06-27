// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.bicycleroad;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
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
 * Opens a dialog to pick the road type ("Fahrradstraße" / "Fahrradzone", a
 * single choice) and any number of additional signs ("Anlieger frei", "KFZ
 * frei", agricultural/forestry/bus exemptions, …). The last choice is
 * remembered in the JOSM preferences and pre-selected the next time, and the OK
 * button is the default — so the common workflow (select way → trigger → press
 * Enter) re-applies the previous combination in one undoable step.
 * <p>
 * The actual key/value pairs and combination rules live in {@link BicycleRoadTags}.
 */
public class BicycleRoadAction extends JosmAction {

    /** Preference key storing the last-used {@link RoadType} (by {@code name()}). */
    static final String PREF_TYPE = "bicycleroad.type";
    /** Preference key storing the last-used {@link AdditionalSign}s (comma-separated {@code name()}s). */
    static final String PREF_SIGNS = "bicycleroad.signs";

    /** Max height (px) of the sign images shown in the dialog. */
    private static final int SIGN_HEIGHT = 48;
    /** Width (px) the sign labels wrap at, so the grid columns stay narrow. */
    private static final int LABEL_WIDTH = 120;
    /** Number of additional-sign options per row before wrapping. */
    private static final int SIGNS_PER_ROW = 3;
    /** Highlight colour of the border around a selected option. */
    private static final Color SELECTED_COLOR = new Color(0x2D7DD2);
    /** Border drawn around a currently selected option. */
    private static final Border SELECTED_BORDER = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(SELECTED_COLOR, 2),
            BorderFactory.createEmptyBorder(3, 5, 3, 5));
    /** Border drawn around unselected options (same footprint, so nothing jumps). */
    private static final Border UNSELECTED_BORDER = BorderFactory.createEmptyBorder(5, 7, 5, 7);

    /**
     * Constructs a new {@code BicycleRoadAction}.
     * <p>
     * The shortcut is registered without a default key binding to avoid clashing
     * with core/other plugins; users can assign one in Preferences &gt; Shortcuts.
     */
    public BicycleRoadAction() {
        super(tr("Tag bicycle road"), "fahrradstrasse",
                tr("Tag the selected way(s) as a Fahrradstraße/Fahrradzone, with optional additional signs."),
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
        Set<AdditionalSign> initialSigns = readSigns();

        JPanel panel = new JPanel(new GridBagLayout());

        // --- Road type: exactly one (radio buttons in a single row) ---
        panel.add(boldLabel(tr("Type")), GBC.eol().insets(0, 0, 0, 4));
        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ButtonGroup typeGroup = new ButtonGroup();
        Map<RoadType, JRadioButton> typeButtons = new EnumMap<>(RoadType.class);
        for (RoadType t : RoadType.values()) {
            JRadioButton rb = new JRadioButton(t.getLabel(), t == type);
            typeGroup.add(rb);
            styleOption(rb, t.getImageName());
            typeButtons.put(t, rb);
            typeRow.add(rb);
        }
        panel.add(typeRow, GBC.eol());

        // --- Additional signs: any number (checkboxes, wrapping after N) ---
        panel.add(boldLabel(tr("Additional signs (optional)")), GBC.eol().insets(0, 12, 0, 4));
        JPanel signGrid = new JPanel(new GridLayout(0, SIGNS_PER_ROW, 8, 4));
        Map<AdditionalSign, JCheckBox> signBoxes = new EnumMap<>(AdditionalSign.class);
        for (AdditionalSign s : AdditionalSign.values()) {
            JCheckBox cb = new JCheckBox(htmlCentered(s.getLabel()), initialSigns.contains(s));
            styleOption(cb, s.getImageName());
            signBoxes.put(s, cb);
            signGrid.add(cb);
        }
        installConflictHandling(signBoxes);
        panel.add(signGrid, GBC.eol());

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
        Set<AdditionalSign> signs = EnumSet.noneOf(AdditionalSign.class);
        for (Map.Entry<AdditionalSign, JCheckBox> entry : signBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                signs.add(entry.getKey());
            }
        }

        // Persist for next time.
        Config.getPref().put(PREF_TYPE, type.name());
        Config.getPref().put(PREF_SIGNS, joinNames(signs));

        Map<String, String> tags = BicycleRoadTags.buildTags(type, signs);

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(ds, selection, tags));
        new Notification(trn("Tagged {0} object as {1}.", "Tagged {0} objects as {1}.",
                selection.size(), selection.size(), describe(type, signs)))
                .setIcon(JOptionPane.INFORMATION_MESSAGE).show();
    }

    /**
     * Wires the checkboxes so that selecting one automatically deselects any
     * sign it conflicts with (see {@link BicycleRoadTags#inConflict}).
     */
    private static void installConflictHandling(Map<AdditionalSign, JCheckBox> signBoxes) {
        for (Map.Entry<AdditionalSign, JCheckBox> entry : signBoxes.entrySet()) {
            AdditionalSign sign = entry.getKey();
            entry.getValue().addItemListener(ev -> {
                if (ev.getStateChange() != ItemEvent.SELECTED) {
                    return;
                }
                for (Map.Entry<AdditionalSign, JCheckBox> other : signBoxes.entrySet()) {
                    if (other.getKey() != sign && BicycleRoadTags.inConflict(sign, other.getKey())) {
                        other.getValue().setSelected(false);
                    }
                }
            });
        }
    }

    /** A bold section header label. */
    private static JLabel boldLabel(String text) {
        return new JLabel("<html><b>" + text + "</b></html>");
    }

    /** Wraps a label in centered HTML constrained to {@link #LABEL_WIDTH}px so long names wrap tidily. */
    private static String htmlCentered(String text) {
        return "<html><div style='text-align:center;width:" + LABEL_WIDTH + "px'>" + text + "</div></html>";
    }

    /**
     * Styles a selectable option: shows the sign image above its label and, since
     * the custom image replaces the native radio/checkbox indicator, marks the
     * selection with a coloured border that tracks the button's state.
     *
     * @param button    the radio button or checkbox to style
     * @param imageName icon name resolved from the jar's {@code images/}, or {@code null} for no image
     */
    private static void styleOption(AbstractButton button, String imageName) {
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.TOP);
        if (imageName != null) {
            ImageIcon icon = new ImageProvider(imageName).setMaxHeight(SIGN_HEIGHT).setOptional(true).get();
            if (icon != null) {
                button.setIcon(icon);
                button.setIconTextGap(6);
            }
        }
        button.addItemListener(e -> updateSelectionStyle(button));
        updateSelectionStyle(button);
    }

    /** Applies the selected/unselected border to {@code button} based on its state. */
    private static void updateSelectionStyle(AbstractButton button) {
        button.setBorder(button.isSelected() ? SELECTED_BORDER : UNSELECTED_BORDER);
        button.setBorderPainted(true);
    }

    /** Builds a short human-readable summary of the applied choice for the toast. */
    private static String describe(RoadType type, Collection<AdditionalSign> signs) {
        if (signs.isEmpty()) {
            return type.getLabel();
        }
        List<String> labels = new ArrayList<>();
        for (AdditionalSign s : signs) {
            labels.add(s.getLabel());
        }
        return type.getLabel() + " + " + String.join(", ", labels);
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

    /** Reads the set of last-used additional signs from the preferences, tolerating bad values. */
    private static Set<AdditionalSign> readSigns() {
        Set<AdditionalSign> result = EnumSet.noneOf(AdditionalSign.class);
        for (String name : Config.getPref().get(PREF_SIGNS, "").split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(AdditionalSign.valueOf(trimmed));
            } catch (IllegalArgumentException ignore) {
                // drop unknown / renamed constants
            }
        }
        return result;
    }

    /** Joins the {@code name()}s of the given signs (in ordinal order) for storage. */
    private static String joinNames(Set<AdditionalSign> signs) {
        List<String> names = new ArrayList<>();
        for (AdditionalSign s : signs) {
            names.add(s.name());
        }
        return String.join(",", names);
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
