// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.tools.ColorHelper;
import org.openstreetmap.josm.tools.Logging;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * One configured STAC API endpoint.
 * <p>
 * Sources are read from the JSON file bundled with the plugin and from the user file handled by
 * {@link StacSources}. A minimal entry only needs a name and the URL of the STAC API:
 * <pre>
 * {"name": "Niedersachsen DOP20", "url": "https://dop.stac.lgln.niedersachsen.de/"}
 * </pre>
 * A source can also describe a WFS, which is what the surveying offices without a STAC API offer
 * as an overview of their aerial images:
 * <pre>
 * {"name": "Brandenburg DOP", "type": "wfs", "url": "https://isk.geobasis-bb.de/ows/aktualitaeten_wfs",
 *  "collections": ["app:dop_single"], "dateProperty": "creationdate"}
 * </pre>
 */
public class StacSource {

    /** STAC item property used for the acquisition date if the source does not name another one. */
    public static final String DEFAULT_DATE_PROPERTY = "datetime";

    /**
     * Kind of service a source describes. Both deliver GeoJSON features with a footprint and a date,
     * so they only differ in the way the request is built.
     */
    public enum Type {
        /** STAC API item search, {@code GET /search?bbox=...}. */
        STAC("stac"),
        /** WFS 2.0 {@code GetFeature} with GeoJSON output. */
        WFS("wfs");

        private final String key;

        Type(String key) {
            this.key = key;
        }

        /**
         * Returns the value of the {@code type} field of the configuration file.
         * @return the key of this type
         */
        public String getKey() {
            return key;
        }

        /**
         * Returns the name shown in the preferences.
         * @return the translated label of this type
         */
        public String getLabel() {
            return this == WFS ? tr("WFS (GeoJSON)") : tr("STAC API");
        }

        /**
         * Returns the type of a {@code type} field.
         * @param key the value read from the configuration, may be {@code null} or empty
         * @return the matching type, {@link #STAC} if the key is empty or unknown
         */
        public static Type fromKey(String key) {
            if (key != null && !key.trim().isEmpty()) {
                for (Type type : values()) {
                    if (type.key.equalsIgnoreCase(key.trim())) {
                        return type;
                    }
                }
                Logging.warn("stacinfo: unknown source type '" + key + "', using " + STAC.key);
            }
            return STAC;
        }
    }

    private String name;
    private String url;
    private Type type = Type.STAC;
    private final List<String> collections = new ArrayList<>();
    private final List<String> layerMatch = new ArrayList<>();
    private final List<String> detailProperties = new ArrayList<>();
    private String dateProperty = DEFAULT_DATE_PROPERTY;
    private String attribution = "";
    private Color color;
    private JsonObject query;
    private boolean enabled = true;
    private boolean sortByDate = true;
    private boolean bundled;
    private List<Pattern> patterns;

    /**
     * Creates a new source.
     * @param name display name, must not be empty
     * @param url STAC API root URL or a complete search URL
     */
    public StacSource(String name, String url) {
        this.name = name == null ? "" : name.trim();
        this.url = url == null ? "" : url.trim();
    }

    /**
     * Reads a source from its JSON representation.
     * @param json the JSON object, as found in the {@code sources} array of a configuration file
     * @return the source, or {@code null} if name or URL are missing
     */
    public static StacSource fromJson(JsonObject json) {
        String name = json.getString("name", "").trim();
        String url = json.getString("url", "").trim();
        if (name.isEmpty() || url.isEmpty()) {
            Logging.warn("stacinfo: ignoring STAC source without name or url: " + json);
            return null;
        }
        StacSource source = new StacSource(name, url);
        source.setType(Type.fromKey(json.getString("type", "")));
        source.setCollections(stringList(json, "collections"));
        source.setLayerMatch(stringList(json, "layerMatch"));
        source.setDetailProperties(stringList(json, "detailProperties"));
        source.setDateProperty(json.getString("dateProperty", DEFAULT_DATE_PROPERTY));
        source.setAttribution(json.getString("attribution", ""));
        String colorString = json.getString("color", "");
        if (!colorString.isEmpty()) {
            Color parsed = ColorHelper.html2color(colorString);
            if (parsed == null) {
                Logging.warn("stacinfo: cannot parse colour " + colorString + " of STAC source " + name);
            }
            source.setColor(parsed);
        }
        source.setQuery(json.getJsonObject("query"));
        source.setEnabled(!json.containsKey("enabled") || json.getBoolean("enabled", true));
        source.setSortByDate(!json.containsKey("sortByDate") || json.getBoolean("sortByDate", true));
        return source;
    }

    private static List<String> stringList(JsonObject json, String key) {
        JsonValue value = json.get(key);
        if (value == null) {
            return Collections.emptyList();
        }
        if (value.getValueType() == JsonValue.ValueType.STRING) {
            return Collections.singletonList(((JsonString) value).getString());
        }
        if (value.getValueType() != JsonValue.ValueType.ARRAY) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (JsonValue entry : (JsonArray) value) {
            if (entry.getValueType() == JsonValue.ValueType.STRING) {
                result.add(((JsonString) entry).getString());
            }
        }
        return result;
    }

    /**
     * Writes this source in the format understood by {@link #fromJson(JsonObject)}.
     * @return the JSON representation of this source
     */
    public JsonObject toJson() {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("name", name);
        builder.add("url", url);
        if (type != Type.STAC) {
            builder.add("type", type.getKey());
        }
        addIfNotEmpty(builder, "collections", collections);
        addIfNotEmpty(builder, "layerMatch", layerMatch);
        addIfNotEmpty(builder, "detailProperties", detailProperties);
        if (!DEFAULT_DATE_PROPERTY.equals(dateProperty)) {
            builder.add("dateProperty", dateProperty);
        }
        if (!attribution.isEmpty()) {
            builder.add("attribution", attribution);
        }
        if (color != null) {
            builder.add("color", ColorHelper.color2html(color));
        }
        if (query != null) {
            builder.add("query", query);
        }
        if (!enabled) {
            builder.add("enabled", false);
        }
        if (!sortByDate) {
            builder.add("sortByDate", false);
        }
        return builder.build();
    }

    private static void addIfNotEmpty(JsonObjectBuilder builder, String key, Collection<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArrayBuilder array = Json.createArrayBuilder();
        values.forEach(array::add);
        builder.add(key, array);
    }

    /**
     * Returns a modifiable copy of this source, used by the preferences dialog.
     * @return a copy of this source
     */
    public StacSource copy() {
        StacSource copy = new StacSource(name, url);
        copy.setType(type);
        copy.setCollections(collections);
        copy.setLayerMatch(layerMatch);
        copy.setDetailProperties(detailProperties);
        copy.setDateProperty(dateProperty);
        copy.setAttribution(attribution);
        copy.setColor(color);
        copy.setQuery(query);
        copy.setEnabled(enabled);
        copy.setSortByDate(sortByDate);
        copy.setBundled(bundled);
        return copy;
    }

    /**
     * Returns the URL used for item searches.
     * @return the search URL, derived from the configured URL if it points at the API root. The URL of
     *         a WFS source is used unchanged, the request parameters are appended to it.
     */
    public String getSearchUrl() {
        String base = url.trim();
        if (type == Type.WFS || base.isEmpty() || base.contains("/search")) {
            return base;
        }
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + "search";
    }

    /**
     * Determines whether this source describes the given imagery layer. The configured regular
     * expressions are matched (case insensitive, as substrings) against name, id and URL of the layer.
     * @param info the imagery layer description, may be {@code null}
     * @return {@code true} if one of the patterns matches
     */
    public boolean matchesLayer(ImageryInfo info) {
        if (info == null || getPatterns().isEmpty()) {
            return false;
        }
        List<String> candidates = Arrays.asList(info.getName(), info.getId(), info.getUrl(), info.getSourceName());
        for (Pattern pattern : getPatterns()) {
            for (String candidate : candidates) {
                if (candidate != null && pattern.matcher(candidate).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized List<Pattern> getPatterns() {
        if (patterns == null) {
            List<Pattern> compiled = new ArrayList<>();
            for (String expression : layerMatch) {
                try {
                    compiled.add(Pattern.compile(expression, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException e) {
                    Logging.warn("stacinfo: invalid layerMatch pattern '" + expression + "' in STAC source " + name);
                    Logging.trace(e);
                }
            }
            patterns = compiled;
        }
        return patterns;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.trim();
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type == null ? Type.STAC : type;
    }

    /**
     * Determines whether this source is queried as a WFS instead of a STAC API.
     * @return {@code true} for a WFS source
     */
    public boolean isWfs() {
        return type == Type.WFS;
    }

    /**
     * Returns the collections of a STAC API or the feature types of a WFS.
     * @return the queried collections, empty for all collections of a STAC API
     */
    public List<String> getCollections() {
        return Collections.unmodifiableList(collections);
    }

    public void setCollections(Collection<String> values) {
        collections.clear();
        if (values != null) {
            values.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(collections::add);
        }
    }

    public List<String> getLayerMatch() {
        return Collections.unmodifiableList(layerMatch);
    }

    public synchronized void setLayerMatch(Collection<String> values) {
        layerMatch.clear();
        patterns = null;
        if (values != null) {
            values.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(layerMatch::add);
        }
    }

    public List<String> getDetailProperties() {
        return Collections.unmodifiableList(detailProperties);
    }

    public void setDetailProperties(Collection<String> values) {
        detailProperties.clear();
        if (values != null) {
            values.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(detailProperties::add);
        }
    }

    public String getDateProperty() {
        return dateProperty;
    }

    public void setDateProperty(String dateProperty) {
        this.dateProperty = dateProperty == null || dateProperty.trim().isEmpty()
                ? DEFAULT_DATE_PROPERTY : dateProperty.trim();
    }

    public String getAttribution() {
        return attribution;
    }

    public void setAttribution(String attribution) {
        this.attribution = attribution == null ? "" : attribution.trim();
    }

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    /**
     * Returns the server side item filter, in the syntax of the STAC query extension.
     * @return the filter, or {@code null} if the source does not restrict the items
     */
    public JsonObject getQuery() {
        return query;
    }

    /**
     * Sets the server side item filter. A plain value is turned into an equality check, so both
     * <code>{"bodenpixelgroesse": "20"}</code> and <code>{"eo:cloud_cover": {"lt": 20}}</code> work.
     * @param query the filter, {@code null} or empty to query all items
     */
    public void setQuery(JsonObject query) {
        this.query = normalizeQuery(query);
    }

    private static JsonObject normalizeQuery(JsonObject query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        JsonObjectBuilder builder = Json.createObjectBuilder();
        for (Map.Entry<String, JsonValue> entry : query.entrySet()) {
            if (entry.getValue().getValueType() == JsonValue.ValueType.OBJECT) {
                builder.add(entry.getKey(), entry.getValue());
            } else {
                builder.add(entry.getKey(), Json.createObjectBuilder().add("eq", entry.getValue()));
            }
        }
        return builder.build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Determines whether the newest items are requested first.
     * @return {@code true} if the query asks the server to sort by date, descending
     */
    public boolean isSortByDate() {
        return sortByDate;
    }

    public void setSortByDate(boolean sortByDate) {
        this.sortByDate = sortByDate;
    }

    /**
     * Determines whether this source comes from the file bundled with the plugin.
     * @return {@code true} for a source shipped with the plugin
     */
    public boolean isBundled() {
        return bundled;
    }

    public void setBundled(boolean bundled) {
        this.bundled = bundled;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        StacSource other = (StacSource) obj;
        return enabled == other.enabled
                && sortByDate == other.sortByDate
                && name.equals(other.name)
                && url.equals(other.url)
                && type == other.type
                && collections.equals(other.collections)
                && layerMatch.equals(other.layerMatch)
                && detailProperties.equals(other.detailProperties)
                && dateProperty.equals(other.dateProperty)
                && attribution.equals(other.attribution)
                && Objects.equals(color, other.color)
                && Objects.equals(query, other.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, url, type, collections, layerMatch, detailProperties, dateProperty, attribution,
                color, enabled, sortByDate, query);
    }
}
