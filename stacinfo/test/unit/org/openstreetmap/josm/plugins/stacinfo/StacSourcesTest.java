// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests of {@link StacSources}, in particular of the merge of bundled and user sources.
 */
class StacSourcesTest {

    @TempDir
    Path directory;

    @AfterEach
    void resetUserFile() {
        StacSources.setUserFile(null);
        StacSources.reload();
    }

    private void useUserFile(String content) {
        Path file = directory.resolve(StacSources.FILE_NAME);
        try {
            if (content != null) {
                Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StacSources.setUserFile(file.toFile());
    }

    private static Optional<StacSource> byName(List<StacSource> sources, String name) {
        return sources.stream().filter(source -> name.equals(source.getName())).findFirst();
    }

    @Test
    void testBundledSourcesAreAvailable() {
        useUserFile(null);
        List<StacSource> sources = StacSources.get();
        assertFalse(sources.isEmpty());
        Optional<StacSource> dop = byName(sources, "Niedersachsen DOP20 (LGLN)");
        assertTrue(dop.isPresent());
        assertEquals("https://dop.stac.lgln.niedersachsen.de/", dop.get().getUrl());
        assertEquals(Collections.singletonList("DOP"), dop.get().getCollections());
        assertTrue(dop.get().isBundled());
    }

    @Test
    void testUserSourceIsAdded() {
        useUserFile("{\"sources\": [{\"name\": \"My STAC\", \"url\": \"https://example.org/stac/\"}]}");
        Optional<StacSource> own = byName(StacSources.get(), "My STAC");
        assertTrue(own.isPresent());
        assertFalse(own.get().isBundled());
        assertTrue(byName(StacSources.get(), "Niedersachsen DOP20 (LGLN)").isPresent());
    }

    @Test
    void testUserSourceOverridesBundledOne() {
        useUserFile("{\"sources\": [{\"name\": \"Niedersachsen DOP20 (LGLN)\", "
                + "\"url\": \"https://example.org/other/\", \"enabled\": false}]}");
        Optional<StacSource> dop = byName(StacSources.get(), "Niedersachsen DOP20 (LGLN)");
        assertTrue(dop.isPresent());
        assertEquals("https://example.org/other/", dop.get().getUrl());
        assertFalse(dop.get().isEnabled());
        assertTrue(dop.get().isBundled());
        assertFalse(StacSources.getEnabled().contains(dop.get()));
        // The bundled list itself is not changed
        assertEquals("https://dop.stac.lgln.niedersachsen.de/",
                byName(StacSources.getBundled(), "Niedersachsen DOP20 (LGLN)").orElseThrow().getUrl());
    }

    @Test
    void testSaveWritesOnlyChangedSources() throws IOException {
        useUserFile(null);
        List<StacSource> sources = new ArrayList<>(StacSources.getBundled());
        StacSource own = new StacSource("My STAC", "https://example.org/stac/");
        sources.add(own);
        StacSources.save(sources);

        String written = new String(Files.readAllBytes(directory.resolve(StacSources.FILE_NAME)), StandardCharsets.UTF_8);
        assertTrue(written.contains("My STAC"));
        assertFalse(written.contains("dop.stac.lgln.niedersachsen.de"), "unchanged bundled sources are not written");

        StacSources.reload();
        assertTrue(byName(StacSources.get(), "My STAC").isPresent());
        assertTrue(byName(StacSources.get(), "Niedersachsen DOP20 (LGLN)").isPresent());
    }

    @Test
    void testSaveWritesModifiedBundledSource() throws IOException {
        useUserFile(null);
        List<StacSource> sources = new ArrayList<>();
        for (StacSource source : StacSources.getBundled()) {
            StacSource copy = source.copy();
            if ("Niedersachsen DGM1 (LGLN)".equals(copy.getName())) {
                copy.setEnabled(false);
            }
            sources.add(copy);
        }
        StacSources.save(sources);
        StacSources.reload();

        StacSource dgm = byName(StacSources.get(), "Niedersachsen DGM1 (LGLN)").orElseThrow();
        assertFalse(dgm.isEnabled());
        assertNotNull(byName(StacSources.get(), "Niedersachsen DOP20 (LGLN)").orElse(null));
    }

    @Test
    void testBrokenUserFileIsIgnored() {
        useUserFile("this is not json");
        assertFalse(StacSources.get().isEmpty());
    }

    @Test
    void testBundledWfsSourceIsAvailable() {
        useUserFile(null);
        StacSource brandenburg = byName(StacSources.get(), "Brandenburg DOP (LGB)").orElseThrow();
        assertTrue(brandenburg.isWfs());
        assertEquals("https://isk.geobasis-bb.de/ows/aktualitaeten_wfs", brandenburg.getUrl());
        assertEquals(Collections.singletonList("app:dop_single"), brandenburg.getCollections());
        assertEquals("creationdate", brandenburg.getDateProperty());
        assertTrue(brandenburg.isEnabled());
    }

    @Test
    void testBundledAlkisSourceIsAvailable() {
        useUserFile(null);
        StacSource alkis = byName(StacSources.get(), "Niedersachsen ALKIS (LGLN)").orElseThrow();
        assertFalse(alkis.isWfs());
        assertEquals("https://alkis.stac.lgln.niedersachsen.de/", alkis.getUrl());
        assertEquals(Collections.singletonList("alkis-landkreise"), alkis.getCollections());
    }

    @Test
    void testDisabledBundledSourceIsNotQueried() {
        useUserFile(null);
        StacSource history = byName(StacSources.get(), "Brandenburg DOP20 history (LGB)").orElseThrow();
        assertFalse(history.isEnabled());
        assertFalse(StacSources.getEnabled().contains(history));
    }
}
