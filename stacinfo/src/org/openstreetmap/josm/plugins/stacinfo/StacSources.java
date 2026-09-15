// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.tools.Logging;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.stream.JsonGenerator;

/**
 * Registry of the configured STAC endpoints.
 * <p>
 * The list is the combination of the sources bundled with the plugin ({@code data/stac_sources.json})
 * and of the user file {@code stac_sources.json} in the plugin preferences directory. A user entry
 * with the same name as a bundled entry replaces it, so bundled sources can be adjusted or switched
 * off without losing them on the next plugin update.
 */
public final class StacSources {

    /** Name of the user file and of the resource bundled with the plugin. */
    public static final String FILE_NAME = "stac_sources.json";

    private static final String BUNDLED_RESOURCE = "/data/" + FILE_NAME;

    private static List<StacSource> bundled;
    private static List<StacSource> merged;
    private static File userFile;

    private StacSources() {
        // Hide public constructor of this utility class
    }

    /**
     * Returns all configured sources, bundled and user defined ones.
     * @return the merged list of sources, in configuration order
     */
    public static synchronized List<StacSource> get() {
        if (merged == null) {
            merged = merge(getBundled(), readUserSources());
        }
        return Collections.unmodifiableList(merged);
    }

    /**
     * Returns the configured sources that are switched on.
     * @return all enabled sources
     */
    public static List<StacSource> getEnabled() {
        return get().stream().filter(StacSource::isEnabled).collect(Collectors.toList());
    }

    /**
     * Returns the sources shipped with the plugin.
     * @return the bundled sources
     */
    public static synchronized List<StacSource> getBundled() {
        if (bundled == null) {
            List<StacSource> sources = new ArrayList<>();
            try (InputStream in = StacSources.class.getResourceAsStream(BUNDLED_RESOURCE)) {
                if (in == null) {
                    Logging.error("stacinfo: bundled STAC sources " + BUNDLED_RESOURCE + " not found");
                } else {
                    sources.addAll(read(in));
                }
            } catch (IOException | RuntimeException e) {
                Logging.error("stacinfo: cannot read bundled STAC sources");
                Logging.error(e);
            }
            sources.forEach(source -> source.setBundled(true));
            bundled = sources;
        }
        return Collections.unmodifiableList(bundled);
    }

    /**
     * Re-reads the user file, for example after it has been edited outside of JOSM.
     */
    public static synchronized void reload() {
        merged = null;
    }

    /**
     * Returns the file the user defined sources are read from and written to.
     * @return the user configuration file, which does not have to exist
     */
    public static synchronized File getUserFile() {
        if (userFile == null) {
            userFile = new File(StacInfoPlugin.getPluginDirectory(), FILE_NAME);
        }
        return userFile;
    }

    /**
     * Overrides the location of the user file, used by the unit tests.
     * @param file the file to read from and write to
     */
    static synchronized void setUserFile(File file) {
        userFile = file;
        merged = null;
    }

    /**
     * Stores the given sources. Entries that are identical to a bundled source are not written, so
     * that later changes of the bundled sources still take effect.
     * @param sources the complete list of sources as shown in the preferences
     * @throws IOException if the user file cannot be written
     */
    public static synchronized void save(Collection<StacSource> sources) throws IOException {
        List<StacSource> bundledSources = getBundled();
        Map<String, StacSource> bundledByName = new LinkedHashMap<>();
        bundledSources.forEach(source -> bundledByName.put(source.getName(), source));

        JsonArrayBuilder array = Json.createArrayBuilder();
        int written = 0;
        for (StacSource source : sources) {
            StacSource bundledSource = bundledByName.get(source.getName());
            if (bundledSource != null && bundledSource.equals(source)) {
                continue;
            }
            array.add(source.toJson());
            written++;
        }
        File file = getUserFile();
        File directory = file.getParentFile();
        if (directory != null && !directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory " + directory);
        }
        JsonObject document = Json.createObjectBuilder().add("sources", array).build();
        JsonWriterFactory factory = Json.createWriterFactory(Collections.singletonMap(JsonGenerator.PRETTY_PRINTING, Boolean.TRUE));
        try (OutputStream out = Files.newOutputStream(file.toPath());
             JsonWriter writer = factory.createWriter(out, StandardCharsets.UTF_8)) {
            writer.writeObject(document);
        }
        Logging.info("stacinfo: saved " + written + " STAC source(s) to " + file);
        merged = null;
    }

    /**
     * Returns the sources configured for the given imagery layer.
     * @param info the imagery layer description
     * @return all enabled sources whose patterns match the layer
     */
    public static List<StacSource> matching(ImageryInfo info) {
        return getEnabled().stream().filter(source -> source.matchesLayer(info)).collect(Collectors.toList());
    }

    /**
     * Returns the sources configured for the background layers currently loaded in JOSM.
     * @return the matching sources, without duplicates, top imagery layer first
     */
    public static List<StacSource> forActiveImageryLayers() {
        List<StacSource> result = new ArrayList<>();
        if (!MainApplication.isDisplayingMapView()) {
            return result;
        }
        for (ImageryLayer layer : MainApplication.getLayerManager().getLayersOfType(ImageryLayer.class)) {
            for (StacSource source : matching(layer.getInfo())) {
                if (!result.contains(source)) {
                    result.add(source);
                }
            }
        }
        return result;
    }

    private static List<StacSource> readUserSources() {
        File file = getUserFile();
        if (!file.isFile()) {
            return Collections.emptyList();
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return read(in);
        } catch (IOException | RuntimeException e) {
            Logging.error("stacinfo: cannot read " + file);
            Logging.error(e);
            return Collections.emptyList();
        }
    }

    private static List<StacSource> read(InputStream in) {
        List<StacSource> sources = new ArrayList<>();
        try (JsonReader reader = Json.createReader(in)) {
            JsonObject root = reader.readObject();
            JsonArray array = root.getJsonArray("sources");
            if (array == null) {
                return sources;
            }
            for (JsonValue value : array) {
                if (value instanceof JsonObject) {
                    StacSource source = StacSource.fromJson((JsonObject) value);
                    if (source != null) {
                        sources.add(source);
                    }
                }
            }
        }
        return sources;
    }

    private static List<StacSource> merge(List<StacSource> bundledSources, List<StacSource> userSources) {
        Map<String, StacSource> userByName = new LinkedHashMap<>();
        userSources.forEach(source -> userByName.put(source.getName(), source));

        List<StacSource> result = new ArrayList<>();
        for (StacSource source : bundledSources) {
            StacSource userSource = userByName.remove(source.getName());
            if (userSource != null) {
                userSource.setBundled(true);
                result.add(userSource);
            } else {
                result.add(source.copy());
            }
        }
        result.addAll(userByName.values());
        return result;
    }
}
