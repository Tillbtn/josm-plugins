// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.bicycleroad;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tag definitions for the bicycle-road plugin and the logic that combines them.
 * <p>
 * A {@link RoadType} supplies the base tags (including a {@code traffic_sign}
 * code). An {@link AdditionalSign} then <i>modifies</i> that base: it appends a
 * suffix to {@code traffic_sign} and may override/add further keys (e.g. relax
 * {@code vehicle=no} to {@code vehicle=destination}). {@link #buildTags} applies
 * both in the right order.
 */
public final class BicycleRoadTags {

    private BicycleRoadTags() {
        // utility holder
    }

    /**
     * The main road type. Exactly one is always selected. The {@code name()} of
     * the constant is what gets persisted in the preferences, so do not rename
     * the constants lightly.
     */
    public enum RoadType {
        /** "Fahrradstraße" (Zeichen 244.1) — a single bicycle road. */
        FAHRRADSTRASSE("Fahrradstraße", "fahrradstrasse", "DE:244.1", "DE:bicycle_road"),
        /** "Fahrradzone" (Zeichen 244.3) — a bicycle zone. */
        FAHRRADZONE("Fahrradzone", "fahrradzone", "DE:244.3", "DE:bicycle_zone");

        private final String label;
        private final String imageName;
        private final String trafficSignCode;
        private final String maxspeedSource;

        RoadType(String label, String imageName, String trafficSignCode, String maxspeedSource) {
            this.label = label;
            this.imageName = imageName;
            this.trafficSignCode = trafficSignCode;
            this.maxspeedSource = maxspeedSource;
        }

        /** @return the human-readable label shown in the dialog. */
        public String getLabel() {
            return label;
        }

        /** @return the icon name (resolved from the jar's {@code images/}, no extension). */
        public String getImageName() {
            return imageName;
        }
    }

    /**
     * An optional additional sign ("Zusatzschild"). {@link #NONE} changes
     * nothing. As with {@link RoadType}, {@code name()} is persisted.
     */
    public enum AdditionalSign {
        /** No additional sign. */
        NONE(tr("None"), null, "", Collections.emptyMap()),
        /** "Anlieger frei" (Zusatzzeichen 1020-30) — destination traffic allowed. */
        ANLIEGER_FREI("Anlieger frei", "anlieger_frei", ",1020-30", anliegerFreiOverrides()),
        /** "KFZ frei" — motor vehicles for destination allowed. */
        KFZ_FREI("KFZ frei", "kfz_frei", ",KFZ frei", kfzFreiOverrides());

        private final String label;
        private final String imageName;
        private final String trafficSignSuffix;
        private final Map<String, String> overrides;

        AdditionalSign(String label, String imageName, String trafficSignSuffix, Map<String, String> overrides) {
            this.label = label;
            this.imageName = imageName;
            this.trafficSignSuffix = trafficSignSuffix;
            this.overrides = overrides;
        }

        /** @return the human-readable label shown in the dialog. */
        public String getLabel() {
            return label;
        }

        /** @return the icon name (resolved from the jar's {@code images/}), or {@code null} for {@link #NONE}. */
        public String getImageName() {
            return imageName;
        }
    }

    /**
     * Builds the full set of tags to write for the given combination.
     *
     * @param type the selected road type (never {@code null})
     * @param sign the selected additional sign (never {@code null})
     * @return an ordered, mutable map of key/value pairs to apply
     */
    public static Map<String, String> buildTags(RoadType type, AdditionalSign sign) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("bicycle", "designated");
        tags.put("bicycle_road", "yes");
        tags.put("traffic_sign", type.trafficSignCode + sign.trafficSignSuffix);
        tags.put("maxspeed", "30");
        tags.put("source:maxspeed", type.maxspeedSource);
        tags.put("vehicle", "no");
        // The sign's overrides win on key clashes (e.g. vehicle=destination
        // replaces vehicle=no) and may add further keys.
        tags.putAll(sign.overrides);
        return tags;
    }

    private static Map<String, String> anliegerFreiOverrides() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("vehicle", "destination"); // replaces vehicle=no from the base
        return m;
    }

    private static Map<String, String> kfzFreiOverrides() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("motor_vehicle", "yes");
        m.put("traffic_sign:note", "Zusatzzeichen: kombiniertes Schild aus 1024-10,1022-12 ohne eigene Nummer");
        return m;
    }
}
