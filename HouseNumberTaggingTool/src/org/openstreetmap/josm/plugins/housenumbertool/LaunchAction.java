// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.housenumbertool;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.Set;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * An action for opening the {@link TagDialog} editor
 */
public class LaunchAction extends JosmAction implements DataSelectionListener {

    private static final long serialVersionUID = -2017126466206457986L;
    private OsmPrimitive selection;

    private final File pluginDir;
    private final Boolean forceLetterIncrement;

    /**
     * Constructs a new {@code LaunchAction}.
     * @param pluginDir plugin directory
     */
    public LaunchAction(File pluginDir) {
        this(pluginDir, null, "HouseNumberTaggingTool", KeyEvent.VK_K, Shortcut.DIRECT, null);
    }

    /**
     * Constructs a new {@code LaunchAction}.
     * 
     * @param pluginDir            plugin directory
     * @param actionId             action identifier (null for default legacy
     *                             behavior)
     * @param name                 action name
     * @param key                  shortcut key
     * @param modifier             shortcut modifier
     * @param forceLetterIncrement if true, force letter increment mode; if false,
     *                             force number increment mode; if null, use last
     *                             saved setting
     */
    public LaunchAction(File pluginDir, String actionId, String name, int key, int modifier,
            Boolean forceLetterIncrement) {
        super(tr(name),
              "home-icon32", 
              tr("Launches the HouseNumberTaggingTool dialog"),
                Shortcut.registerShortcut("edit:housenumbertaggingtool" + (actionId == null ? "" : ":" + actionId),
                        tr("Data: {0}", tr(name)),
                        key, modifier),
              true);

        this.pluginDir = pluginDir;
        this.forceLetterIncrement = forceLetterIncrement;
        SelectionEventManager.getInstance().addSelectionListener(this);
        setEnabled(false);
    }

    /**
     * launch the editor
     */
    protected void launchEditor() {
        if (!isEnabled()) {
            return;
        }
      
        TagDialog dialog = new TagDialog(pluginDir, selection, forceLetterIncrement);
        dialog.showDialog();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        launchEditor();
    }

    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        Set<OsmPrimitive> newSelection = event.getSelection();
        if (newSelection != null && newSelection.size() == 1) {
            setEnabled(true);
            selection = newSelection.iterator().next();
        } else {
            setEnabled(false);
            selection = null;
        }
    }
}
