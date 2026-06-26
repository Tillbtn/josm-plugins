// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.checkdate;

import javax.swing.JMenuItem;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Destroyable;

/**
 * Plugin entry point. JOSM instantiates this class once, passing the plugin's
 * {@link PluginInformation}. The constructor registers {@link CheckDateAction}
 * in the Data menu; {@link #destroy()} removes it again so the plugin can be
 * enabled/disabled at runtime without restarting JOSM.
 */
public class CheckDatePlugin extends Plugin implements Destroyable {

    private final CheckDateAction action = new CheckDateAction();
    private final JMenuItem menuItem;

    /**
     * Creates the plugin and hooks the action into the Data menu.
     * @param info information about the plugin and its local installation
     */
    public CheckDatePlugin(PluginInformation info) {
        super(info);
        menuItem = MainMenu.add(MainApplication.getMenu().dataMenu, action);
    }

    @Override
    public void destroy() {
        MainApplication.getMenu().dataMenu.remove(menuItem);
        action.destroy();
    }
}
