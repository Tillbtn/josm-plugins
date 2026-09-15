// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Plugin showing the currency of background imagery layers by querying the STAC API of the provider.
 */
public class StacInfoPlugin extends Plugin {

    private static StacInfoPlugin instance;

    private StacInfoDialog dialog;

    /**
     * Creates the plugin.
     * @param info the plugin information provided by JOSM
     */
    public StacInfoPlugin(PluginInformation info) {
        super(info);
        instance = this;
        MainMenu.add(MainApplication.getMenu().imageryMenu, new ShowDatesAction());
    }

    /**
     * Returns the running instance of the plugin.
     * @return the plugin instance, or {@code null} if it is not loaded
     */
    public static StacInfoPlugin getInstance() {
        return instance;
    }

    /**
     * Returns the directory the user configuration of the plugin is stored in.
     * @return the plugin preferences directory
     */
    public static File getPluginDirectory() {
        StacInfoPlugin plugin = instance;
        if (plugin != null) {
            return plugin.getPluginDirs().getPreferencesDirectory(true);
        }
        return new File(Config.getDirs().getPreferencesDirectory(true), "plugins" + File.separator + "stacinfo");
    }

    /**
     * Returns the layer showing the STAC footprints.
     * @return the layer, or {@code null} if it does not exist
     */
    public static StacLayer findLayer() {
        if (!MainApplication.isDisplayingMapView()) {
            return null;
        }
        List<StacLayer> layers = MainApplication.getLayerManager().getLayersOfType(StacLayer.class);
        return layers.isEmpty() ? null : layers.get(0);
    }

    /**
     * Returns the dialog of the plugin.
     * @return the dialog, or {@code null} if no map frame is open
     */
    public StacInfoDialog getDialog() {
        return dialog;
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            dialog = new StacInfoDialog();
            newFrame.addToggleDialog(dialog);
        } else {
            dialog = null;
        }
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new StacPreferenceSetting();
    }

    /**
     * Opens the dialog and loads the dates of the current map view.
     */
    private static class ShowDatesAction extends JosmAction {
        ShowDatesAction() {
            super(tr("Imagery dates (STAC)"), "dialogs/stacinfo",
                    tr("Show the acquisition dates of the background imagery in the current map view"), null, false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            StacInfoPlugin plugin = getInstance();
            StacInfoDialog currentDialog = plugin == null ? null : plugin.getDialog();
            if (currentDialog == null) {
                return;
            }
            currentDialog.unfurlDialog();
            StacLayer layer = currentDialog.getOrCreateLayer();
            if (layer != null) {
                layer.refresh(true);
            }
        }

        @Override
        protected void updateEnabledState() {
            setEnabled(MainApplication.isDisplayingMapView());
        }
    }
}
