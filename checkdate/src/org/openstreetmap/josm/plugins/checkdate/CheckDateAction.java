// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.checkdate;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.time.LocalDate;
import java.util.Collection;
import java.util.regex.Pattern;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Writes {@code check_date=<date>} onto every selected primitive.
 * <p>
 * The dialog is pre-filled with today's date and its OK button is the default,
 * so the common "I surveyed this today, stamp it" case is a single Enter press.
 * Any ISO-8601 date ({@code YYYY}, {@code YYYY-MM} or {@code YYYY-MM-DD}) may be
 * typed instead.
 */
public class CheckDateAction extends JosmAction {

    /** The OSM key written by this action. */
    static final String KEY = "check_date";

    /** Accepts ISO-8601 calendar dates: {@code YYYY}, {@code YYYY-MM} or {@code YYYY-MM-DD}. */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}(-\\d{2}(-\\d{2})?)?");

    /**
     * Constructs a new {@code CheckDateAction}.
     * <p>
     * The shortcut is registered without a default key binding to avoid clashing
     * with core/other plugins; users can assign one in Preferences &gt; Shortcuts.
     */
    public CheckDateAction() {
        super(tr("Set check_date"), "checkdate",
                tr("Set check_date=* on the selected objects (defaults to today''s date)."),
                Shortcut.registerShortcut("data:checkdate", tr("Data: {0}", tr("Set check_date")),
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

        JosmTextField dateField = new JosmTextField(10);
        dateField.setText(LocalDate.now().toString());
        dateField.selectAll();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(trn("Set {0} on {1} selected object:", "Set {0} on {1} selected objects:",
                selection.size(), KEY, selection.size())), GBC.eol().insets(0, 0, 0, 8));
        panel.add(new JLabel(tr("Date") + ' '), GBC.std());
        panel.add(dateField, GBC.eol().fill(GridBagConstraints.HORIZONTAL));
        panel.add(new JLabel("<html><i>" + tr("Format: YYYY-MM-DD") + "</i></html>"), GBC.eol().insets(0, 4, 0, 0));

        ExtendedDialog dialog = new ExtendedDialog(MainApplication.getMainFrame(),
                tr("Set check_date"), tr("Set date"), tr("Cancel"));
        dialog.setButtonIcons("ok", "cancel");
        dialog.setIcon(JOptionPane.QUESTION_MESSAGE);
        dialog.setContent(panel);
        dialog.setDefaultButton(1);
        dialog.showDialog();

        if (dialog.getValue() != 1) {
            return;
        }

        String value = dateField.getText().trim();
        if (!DATE_PATTERN.matcher(value).matches()) {
            new Notification(tr("''{0}'' is not a valid date (expected YYYY-MM-DD).", value))
                    .setIcon(JOptionPane.ERROR_MESSAGE).show();
            return;
        }

        UndoRedoHandler.getInstance().add(new ChangePropertyCommand(selection, KEY, value));
        new Notification(trn("Set {0}={1} on {2} object.", "Set {0}={1} on {2} objects.",
                selection.size(), KEY, value, selection.size()))
                .setIcon(JOptionPane.INFORMATION_MESSAGE).show();
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
