// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.housenumbertool;

import java.awt.event.KeyEvent;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Simple tool to tag house numbers. Select house and press 'k'. Select your addr-tags and press OK.
 */
public class HouseNumberTaggingToolPlugin extends Plugin {

    /**
     * constructor
     * @param info plugin info
     */
    public HouseNumberTaggingToolPlugin(PluginInformation info) {
        super(info);
        LaunchAction actionKey = new LaunchAction(getPluginDirs().getUserDataDirectory(false));
        MainMenu.add(MainApplication.getMenu().dataMenu, actionKey, false, 0);

        LaunchAction actionShiftKey = new LaunchAction(getPluginDirs().getUserDataDirectory(false),
                "IncrementLetter",
                "HouseNumberTaggingTool (Increment Letter)",
                KeyEvent.VK_K,
                Shortcut.SHIFT,
                true);
        MainMenu.add(MainApplication.getMenu().dataMenu, actionShiftKey, false, 0);
    }
}
