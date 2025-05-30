// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.fr.cadastre.download;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.downloadtasks.DownloadOsmTask;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DownloadPolicy;
import org.openstreetmap.josm.data.osm.UploadPolicy;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.fr.cadastre.api.CadastreAPI;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * Cadastre download task.
 */
public class CadastreDownloadTask extends DownloadOsmTask {

    private static final String CADASTRE_URL = "https://cadastre.data.gouv.fr/data/dgfip-pci-vecteur/latest/edigeo/feuilles";

    private final CadastreDownloadData data;

    /**
     * Constructs a new {@code CadastreDownloadTask} with default behaviour.
     */
    public CadastreDownloadTask() {
        this(new CadastreDownloadData(true, true, true, true, true, true, true, true, true), true);
    }

    /**
     * Constructs a new {@code CadastreDownloadTask} with parameterizable behaviour.
     * @param data defines which data has to be downloaded
     * @param zoomToData if true, the map view will zoom to download area after download
     */
    public CadastreDownloadTask(CadastreDownloadData data, boolean zoomToData) {
        this.data = Objects.requireNonNull(data);
        setZoomAfterDownload(zoomToData);
    }

    @Override
    public Future<?> download(DownloadParams settings, Bounds downloadArea, ProgressMonitor progressMonitor) {
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (String id : CadastreAPI.getSheets(downloadArea)) {
                String url = String.join("/", CADASTRE_URL, id.substring(0, id.startsWith("97") ? 3 : 2),
                        id.substring(0, 5), "edigeo-"+id+".tar.bz2");
                tasks.add(MainApplication.worker.submit(new InternalDownloadTask(settings, url, progressMonitor, zoomAfterDownload)));
            }
        } catch (IOException e) {
            Logging.error(e);
            new Notification(Utils.escapeReservedCharactersHTML(Utils.getRootCause(e).getMessage()))
                .setIcon(JOptionPane.ERROR_MESSAGE).show();
        }
        return MainApplication.worker.submit(() -> {
            for (Future<?> f : tasks) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    Logging.error(e);
                } catch (InterruptedException e) {
                    Logging.error(e);
                    Thread.currentThread().interrupt();
                }
            }
            List<CadastreDataLayer> layers = MainApplication.getLayerManager().getLayersOfType(CadastreDataLayer.class);
            if (layers.size() > 1 && Config.getPref().getBoolean("cadastrewms.merge.data.layers")) {
                GuiHelper.runInEDT(() -> MainApplication.getMenu().merge.merge(layers));
            }
        });
    }

    @Override
    public Future<?> loadUrl(DownloadParams settings, String url, ProgressMonitor progressMonitor) {
        downloadTask = new InternalDownloadTask(settings, url, progressMonitor, zoomAfterDownload);
        currentBounds = null;
        return MainApplication.worker.submit(downloadTask);
    }

    @Override
    public String[] getPatterns() {
        return new String[]{"https?://.*edigeo.*.tar.bz2"};
    }

    @Override
    public String getTitle() {
        return tr("Download cadastre data");
    }

    static class CadastreDataLayer extends OsmDataLayer {

        CadastreDataLayer(DataSet data, String name, File associatedFile) {
            super(data, name, associatedFile);
        }
    }

    class InternalDownloadTask extends DownloadTask {

        private final String url;

        InternalDownloadTask(DownloadParams settings, String url, ProgressMonitor progressMonitor, boolean zoom) {
            super(settings, new CadastreServerReader(url, data), progressMonitor, zoom);
            this.url = url;
        }

        @Override
        protected CadastreDataLayer createNewLayer(DataSet dataset, Optional<String> layerName) {
            String realLayerName = layerName.isPresent() ? layerName.get() : url.substring(url.lastIndexOf('/')+1);
            if (realLayerName == null || realLayerName.isEmpty()) {
                realLayerName = settings.getLayerName();
            }
            if (realLayerName == null || realLayerName.isEmpty()) {
                realLayerName = OsmDataLayer.createNewName();
            }
            dataSet.setDownloadPolicy(DownloadPolicy.BLOCKED);
            dataSet.setUploadPolicy(UploadPolicy.BLOCKED);
            return new CadastreDataLayer(dataSet, realLayerName, null);
        }

        private Stream<CadastreDataLayer> getCadastreDataLayers() {
            return MainApplication.getLayerManager().getLayersOfType(CadastreDataLayer.class).stream();
        }

        @Override
        protected long getNumModifiableDataLayers() {
            return getCadastreDataLayers().count();
        }

        @Override
        protected CadastreDataLayer getFirstModifiableDataLayer() {
            return getCadastreDataLayers().findFirst().orElse(null);
        }
    }
}
