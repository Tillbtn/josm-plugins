// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.bicycleroad;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tag definitions for the bicycle-road plugin and the logic that combines them.
 * <p>
 * A {@link RoadType} supplies the base tags (including a {@code traffic_sign}
 * code). Any number of {@link AdditionalSign}s then <i>modify</i> that base:
 * each appends its code to {@code traffic_sign} and contributes override keys
 * (e.g. relax {@code vehicle=no} to {@code vehicle=destination}). When several
 * signs touch the same key, their values are merged with {@code ;}.
 * {@link #buildTags} applies everything in a deterministic order.
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
     * An optional additional sign ("Zusatzschild"). Any number may be selected
     * at once; {@link #CONFLICTING_PAIRS} lists the combinations that are not
     * allowed. As with {@link RoadType}, {@code name()} is persisted.
     */
    public enum AdditionalSign {
        /** "Anlieger frei" (Zusatzzeichen 1020-30) — destination traffic allowed. */
        ANLIEGER_FREI("Anlieger frei", "anlieger_frei", "1020-30", Map.of("vehicle", "destination")),
        /** "KFZ frei" — motor vehicles allowed. */
        KFZ_FREI("KFZ frei", "kfz_frei", "KFZ frei", Map.of("motor_vehicle", "yes",
                "traffic_sign:note", "Zusatzzeichen: kombiniertes Schild aus 1024-10,1022-12 ohne eigene Nummer")),
        /** "Landwirtschaftlicher Verkehr frei" (Zusatzzeichen 1026-36). */
        LANDWIRTSCHAFT("Landwirtschaftlicher Verkehr frei", "landwirtschaft", "1026-36",
                Map.of("vehicle", "agricultural")),
        /** "Forstwirtschaftlicher Verkehr frei" (Zusatzzeichen 1026-37). */
        FORSTWIRTSCHAFT("Forstwirtschaftlicher Verkehr frei", "forstwirtschaft", "1026-37",
                Map.of("vehicle", "forestry")),
        /** "Land- und Forstwirtschaftlicher Verkehr frei" (Zusatzzeichen 1026-38). */
        LAND_FORST("Land- und Forstwirtschaftlicher Verkehr frei", "land_forst", "1026-38",
                Map.of("vehicle", "agricultural;forestry")),
        /** "Linienverkehr frei" (Zusatzzeichen 1026-32) — scheduled buses allowed. */
        LINIENVERKEHR("Linienverkehr frei", "linienverkehr", "1026-32", Map.of("bus", "yes"));

        private final String label;
        private final String imageName;
        private final String trafficSignCode;
        private final Map<String, String> overrides;

        AdditionalSign(String label, String imageName, String trafficSignCode, Map<String, String> overrides) {
            this.label = label;
            this.imageName = imageName;
            this.trafficSignCode = trafficSignCode;
            this.overrides = overrides;
        }

        /** @return the human-readable label shown in the dialog. */
        public String getLabel() {
            return label;
        }

        /** @return the icon name (resolved from the jar's {@code images/}). */
        public String getImageName() {
            return imageName;
        }
    }

    /**
     * Optional keys a sign may set that must be cleared again when no currently
     * selected sign sets them — so a leftover {@code motor_vehicle=yes} or
     * {@code bus=yes} from a previous application is removed. {@code vehicle} is
     * excluded because {@link #buildTags} always (re)writes it.
     */
    private static final Set<String> MANAGED_OPTIONAL_KEYS = computeManagedOptionalKeys();

    private static Set<String> computeManagedOptionalKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (AdditionalSign s : AdditionalSign.values()) {
            keys.addAll(s.overrides.keySet());
        }
        keys.remove("vehicle");
        return keys;
    }

    /**
     * Decides whether two signs may be selected together.
     * <p>
     * Only "Anlieger frei" is meant to be combined with another sign — and not
     * with "KFZ frei" (which grants general motor access, contradicting the
     * destination-only restriction). Every other pair is mutually exclusive
     * (e.g. "KFZ frei" + "Linienverkehr frei", or two overlapping
     * agricultural/forestry signs make no sense together).
     *
     * @param a one sign
     * @param b another sign
     * @return {@code true} if {@code a} and {@code b} cannot be selected together
     */
    public static boolean inConflict(AdditionalSign a, AdditionalSign b) {
        if (a == b) {
            return false;
        }
        if (a == AdditionalSign.ANLIEGER_FREI || b == AdditionalSign.ANLIEGER_FREI) {
            // Anlieger frei combines with anything except KFZ frei.
            return a == AdditionalSign.KFZ_FREI || b == AdditionalSign.KFZ_FREI;
        }
        // Two non-Anlieger signs are always mutually exclusive.
        return true;
    }

    /**
     * Builds the full set of tags to write for the given combination.
     *
     * @param type  the selected road type (never {@code null})
     * @param signs the selected additional signs (may be empty, never {@code null})
     * @return an ordered, mutable map of key/value pairs to apply
     */
    public static Map<String, String> buildTags(RoadType type, Collection<AdditionalSign> signs) {
        // Process signs in a stable order (by declaration) regardless of click order.
        List<AdditionalSign> ordered = new ArrayList<>(signs);
        ordered.sort(Comparator.comparingInt(Enum::ordinal));

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("bicycle", "designated");
        tags.put("bicycle_road", "yes");

        StringBuilder trafficSign = new StringBuilder(type.trafficSignCode);
        for (AdditionalSign s : ordered) {
            trafficSign.append(',').append(s.trafficSignCode);
        }
        tags.put("traffic_sign", trafficSign.toString());

        tags.put("maxspeed", "30");
        tags.put("source:maxspeed", type.maxspeedSource);
        tags.put("vehicle", "no");

        // Merge the signs' overrides. Multiple signs can contribute to the same
        // key (e.g. vehicle); their values are unioned and joined with ';'. A
        // key that any sign sets replaces the base value (so vehicle=no is gone
        // as soon as a sign relaxes it).
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        for (AdditionalSign s : ordered) {
            for (Map.Entry<String, String> e : s.overrides.entrySet()) {
                LinkedHashSet<String> values = merged.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>());
                for (String v : e.getValue().split(";")) {
                    values.add(v);
                }
            }
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : merged.entrySet()) {
            tags.put(e.getKey(), String.join(";", e.getValue()));
        }

        // Clear optional keys that a previous application may have set but that
        // no currently selected sign sets (empty value => key removed).
        for (String key : MANAGED_OPTIONAL_KEYS) {
            tags.putIfAbsent(key, "");
        }
        return tags;
    }
}
