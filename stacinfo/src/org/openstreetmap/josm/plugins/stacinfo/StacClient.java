// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/**
 * Queries the item search endpoint of a STAC API or the {@code GetFeature} operation of a WFS.
 * <p>
 * Both answer with a GeoJSON {@code FeatureCollection}, so only the request differs: a STAC API is
 * paged through its {@code rel=next} links, a WFS through {@code STARTINDEX}.
 *
 * @see <a href="https://github.com/radiantearth/stac-api-spec/tree/main/item-search">STAC API item search</a>
 * @see <a href="https://docs.ogc.org/is/09-025r2/09-025r2.html">OGC WFS 2.0</a>
 */
public final class StacClient {

    /** Maximum number of result pages followed for one search. */
    public static final int MAX_PAGES = 20;

    /** WFS version used for the {@code GetFeature} requests. */
    public static final String WFS_VERSION = "2.0.0";

    /**
     * CRS of the WFS requests. The URN form is used deliberately: it selects the axis order defined by
     * EPSG, which is latitude before longitude, both for {@code BBOX} and for the returned geometries.
     */
    private static final String WFS_CRS = "urn:ogc:def:crs:EPSG::4326";

    private static final String WFS_FORMAT = "application/geo+json";

    private static final int PAGE_SIZE = 200;
    private static final int TIMEOUT = 30_000;

    private StacClient() {
        // Hide public constructor of this utility class
    }

    /**
     * Searches all items of a source that intersect the given area.
     * @param source the STAC endpoint to query
     * @param area the area of interest
     * @param maxItems the maximum number of items to return
     * @return the items, sorted by date, newest first
     * @throws IOException if the endpoint cannot be reached or answers with an error
     */
    public static List<StacItem> search(StacSource source, Bounds area, int maxItems) throws IOException {
        return source.isWfs() ? searchWfs(source, area, maxItems) : searchStac(source, area, maxItems);
    }

    private static List<StacItem> searchStac(StacSource source, Bounds area, int maxItems) throws IOException {
        List<StacItem> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenUrls = new HashSet<>();
        boolean sortByDate = source.isSortByDate();
        String url = buildSearchUrl(source, area, Math.min(maxItems, PAGE_SIZE), sortByDate);
        for (int page = 0; page < MAX_PAGES && url != null && items.size() < maxItems; page++) {
            if (!seenUrls.add(url)) {
                Logging.warn("stacinfo: STAC endpoint " + source.getName() + " returned a loop of next links");
                break;
            }
            JsonObject response;
            try {
                response = get(url, source);
            } catch (HttpStatusException e) {
                if (page == 0 && sortByDate && e.getCode() >= 400 && e.getCode() < 500) {
                    // The endpoint does not understand the sort parameter, try again without it
                    Logging.info("stacinfo: " + source.getName() + " does not support sorting by date, retrying unsorted");
                    sortByDate = false;
                    url = buildSearchUrl(source, area, Math.min(maxItems, PAGE_SIZE), false);
                    seenUrls.add(url);
                    response = get(url, source);
                } else {
                    throw e;
                }
            }
            JsonArray features = response.getJsonArray("features");
            if (features == null) {
                break;
            }
            addFeatures(features, source, items, seenIds, maxItems);
            url = features.isEmpty() ? null : nextLink(response);
        }
        items.sort(StacClient::compareByDate);
        return items;
    }

    /**
     * Searches all features of a WFS source that intersect the given area. Every configured feature
     * type is requested on its own, so that a source can combine several of them.
     * @param source the WFS endpoint to query
     * @param area the area of interest
     * @param maxItems the maximum number of items to return
     * @return the items, sorted by date, newest first
     * @throws IOException if the endpoint cannot be reached or answers with an error
     */
    private static List<StacItem> searchWfs(StacSource source, Bounds area, int maxItems) throws IOException {
        if (source.getCollections().isEmpty()) {
            throw new IOException(tr("The WFS source ''{0}'' does not name a feature type. Enter one in the "
                    + "field ''Collections'', for example app:dop_single.", source.getName()));
        }
        List<StacItem> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        boolean sortByDate = source.isSortByDate();
        for (String typeName : source.getCollections()) {
            int startIndex = 0;
            for (int page = 0; page < MAX_PAGES && items.size() < maxItems; page++) {
                int count = Math.min(maxItems - items.size(), PAGE_SIZE);
                JsonObject response;
                try {
                    response = get(buildWfsUrl(source, typeName, area, count, startIndex, sortByDate), source);
                } catch (HttpStatusException e) {
                    if (page == 0 && sortByDate && e.getCode() >= 400 && e.getCode() < 500) {
                        // The service does not understand the sort parameter, try again without it
                        Logging.info("stacinfo: " + source.getName() + " does not support sorting by date, retrying unsorted");
                        sortByDate = false;
                        response = get(buildWfsUrl(source, typeName, area, count, startIndex, false), source);
                    } else {
                        throw e;
                    }
                }
                JsonArray features = response.getJsonArray("features");
                if (features == null || features.isEmpty()) {
                    break;
                }
                addFeatures(features, source, items, seenIds, maxItems);
                startIndex += features.size();
                if (features.size() < count) {
                    break;
                }
            }
        }
        items.sort(StacClient::compareByDate);
        return items;
    }

    /**
     * Turns the features of one result page into items and appends the ones that are still missing.
     * @param features the {@code features} array of the answer
     * @param source the queried source
     * @param items the items collected so far, extended by this call
     * @param seenIds the ids already collected, extended by this call
     * @param maxItems the maximum number of items
     */
    private static void addFeatures(JsonArray features, StacSource source, List<StacItem> items,
            Set<String> seenIds, int maxItems) {
        for (JsonValue feature : features) {
            if (items.size() >= maxItems) {
                return;
            }
            if (!(feature instanceof JsonObject)) {
                continue;
            }
            StacItem item = StacItem.fromJson((JsonObject) feature, source.getDateProperty());
            // Features without an id cannot be told apart, so they are all kept
            if (item != null && (item.getId().isEmpty() || seenIds.add(item.getId()))) {
                items.add(item);
            }
        }
    }

    /**
     * Compares two items so that the newest one comes first; items without a date come last.
     * @param first the first item
     * @param second the second item
     * @return a negative value, zero or a positive value as in {@link java.util.Comparator}
     */
    static int compareByDate(StacItem first, StacItem second) {
        if (first.getDate() == null || second.getDate() == null) {
            if (first.getDate() != second.getDate()) {
                return first.getDate() == null ? 1 : -1;
            }
        } else {
            int byDate = second.getDate().compareTo(first.getDate());
            if (byDate != 0) {
                return byDate;
            }
        }
        return first.getId().compareTo(second.getId());
    }

    /**
     * Builds the URL of the first result page.
     * @param source the STAC endpoint to query
     * @param area the area of interest
     * @param limit the page size
     * @return the search URL
     */
    public static String buildSearchUrl(StacSource source, Bounds area, int limit) {
        return buildSearchUrl(source, area, limit, source.isSortByDate());
    }

    /**
     * Builds the URL of the first result page.
     * @param source the STAC endpoint to query
     * @param area the area of interest
     * @param limit the page size
     * @param sortByDate whether the server is asked to return the newest items first
     * @return the search URL
     */
    public static String buildSearchUrl(StacSource source, Bounds area, int limit, boolean sortByDate) {
        StringBuilder url = new StringBuilder(source.getSearchUrl());
        url.append(url.indexOf("?") >= 0 ? '&' : '?')
           .append("bbox=")
           .append(coordinate(area.getMinLon())).append(',')
           .append(coordinate(area.getMinLat())).append(',')
           .append(coordinate(area.getMaxLon())).append(',')
           .append(coordinate(area.getMaxLat()))
           .append("&limit=").append(Math.max(1, limit));
        if (!source.getCollections().isEmpty()) {
            url.append("&collections=").append(encode(String.join(",", source.getCollections())));
        }
        if (source.getQuery() != null) {
            url.append("&query=").append(encode(source.getQuery().toString()));
        }
        if (sortByDate) {
            url.append("&sortby=").append(encode("-properties.datetime"));
        }
        return url.toString();
    }

    /**
     * Builds the URL of one {@code GetFeature} result page.
     * @param source the WFS endpoint to query
     * @param typeName the queried feature type, for example {@code app:dop_single}
     * @param area the area of interest
     * @param count the page size
     * @param startIndex the index of the first returned feature
     * @param sortByDate whether the service is asked to return the newest features first
     * @return the request URL
     */
    public static String buildWfsUrl(StacSource source, String typeName, Bounds area, int count, int startIndex,
            boolean sortByDate) {
        StringBuilder url = new StringBuilder(source.getSearchUrl());
        url.append(url.indexOf("?") >= 0 ? '&' : '?')
           .append("SERVICE=WFS&VERSION=").append(WFS_VERSION)
           .append("&REQUEST=GetFeature")
           .append("&TYPENAMES=").append(encode(typeName))
           .append("&OUTPUTFORMAT=").append(encode(WFS_FORMAT))
           .append("&SRSNAME=").append(encode(WFS_CRS))
           .append("&BBOX=")
           .append(coordinate(area.getMinLat())).append(',')
           .append(coordinate(area.getMinLon())).append(',')
           .append(coordinate(area.getMaxLat())).append(',')
           .append(coordinate(area.getMaxLon())).append(',')
           .append(encode(WFS_CRS))
           .append("&COUNT=").append(Math.max(1, count));
        if (startIndex > 0) {
            url.append("&STARTINDEX=").append(startIndex);
        }
        if (sortByDate) {
            url.append("&SORTBY=").append(encode(source.getDateProperty() + " D"));
        }
        return url.toString();
    }

    /**
     * Reads the collections offered by a STAC API or the feature types offered by a WFS, used to check
     * a newly entered URL.
     * @param source the source to query
     * @return the collections, each entry being {@code {id, title}}
     * @throws IOException if the endpoint cannot be reached or answers with an error
     */
    public static List<String[]> collections(StacSource source) throws IOException {
        if (source.isWfs()) {
            return featureTypes(source);
        }
        String base = source.getUrl().trim();
        int search = base.indexOf("/search");
        if (search >= 0) {
            base = base.substring(0, search);
        }
        if (!base.endsWith("/")) {
            base += "/";
        }
        JsonObject response = get(base + "collections", source);
        JsonArray array = response.getJsonArray("collections");
        List<String[]> result = new ArrayList<>();
        if (array != null) {
            for (JsonValue value : array) {
                if (value instanceof JsonObject) {
                    JsonObject collection = (JsonObject) value;
                    result.add(new String[] {collection.getString("id", ""), collection.getString("title", "")});
                }
            }
        }
        return result;
    }

    /**
     * Reads the feature types a WFS offers from its capabilities.
     * @param source the WFS source to query
     * @return the feature types, each entry being {@code {name, title}}
     * @throws IOException if the service cannot be reached or answers with an error
     */
    private static List<String[]> featureTypes(StacSource source) throws IOException {
        String base = source.getSearchUrl();
        String url = base + (base.contains("?") ? "&" : "?")
                + "SERVICE=WFS&VERSION=" + WFS_VERSION + "&REQUEST=GetCapabilities";
        Document document = getXml(url, source);
        List<String[]> result = new ArrayList<>();
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Node node = elements.item(i);
            if (node instanceof Element && "FeatureType".equals(localName(node))) {
                String name = childText((Element) node, "Name");
                if (!name.isEmpty()) {
                    result.add(new String[] {name, childText((Element) node, "Title")});
                }
            }
        }
        return result;
    }

    /**
     * Returns the tag name of a node without its namespace prefix, which lets the capabilities be read
     * without knowing whether the parser was namespace aware.
     * @param node the node
     * @return the local name of the node
     */
    private static String localName(Node node) {
        String name = node.getLocalName();
        if (name == null) {
            name = node.getNodeName();
            int colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(colon + 1);
            }
        }
        return name;
    }

    private static String childText(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && name.equals(localName(child))) {
                String text = child.getTextContent();
                return text == null ? "" : text.trim();
            }
        }
        return "";
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String nextLink(JsonObject response) {
        JsonArray links = response.getJsonArray("links");
        if (links == null) {
            return null;
        }
        for (JsonValue value : links) {
            if (!(value instanceof JsonObject)) {
                continue;
            }
            JsonObject link = (JsonObject) value;
            if ("next".equals(link.getString("rel", "")) && !"POST".equalsIgnoreCase(link.getString("method", "GET"))) {
                String href = link.getString("href", "");
                if (!href.isEmpty()) {
                    return href;
                }
            }
        }
        return null;
    }

    private static Document getXml(String url, StacSource source) throws IOException {
        HttpClient.Response response = connect(url, source, "application/xml, text/xml, */*;q=0.8");
        try {
            checkStatus(response, source);
            try (InputStream in = response.getContent()) {
                return XmlUtils.parseSafeDOM(in);
            } catch (ParserConfigurationException | SAXException | RuntimeException e) {
                throw new IOException(tr("Cannot read the answer of STAC source ''{0}''", source.getName()), e);
            }
        } finally {
            response.disconnect();
        }
    }

    private static JsonObject get(String url, StacSource source) throws IOException {
        HttpClient.Response response = connect(url, source, "application/geo+json, application/json;q=0.9");
        try {
            checkStatus(response, source);
            try (InputStream in = response.getContent(); JsonReader reader = Json.createReader(in)) {
                return reader.readObject();
            } catch (RuntimeException e) {
                throw new IOException(tr("Cannot read the answer of STAC source ''{0}''", source.getName()), e);
            }
        } finally {
            response.disconnect();
        }
    }

    private static void checkStatus(HttpClient.Response response, StacSource source) throws HttpStatusException {
        if (response.getResponseCode() != 200) {
            throw new HttpStatusException(response.getResponseCode(), tr("STAC source ''{0}'' answered with {1} {2}",
                    source.getName(), response.getResponseCode(), response.getResponseMessage()));
        }
    }

    private static HttpClient.Response connect(String url, StacSource source, String accept) throws IOException {
        HttpClient client;
        try {
            client = HttpClient.create(new URI(url).toURL());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException(tr("Invalid URL of STAC source ''{0}'': {1}", source.getName(), url), e);
        }
        client.setAccept(accept);
        client.setReasonForRequest("stacinfo: " + source.getName());
        client.setConnectTimeout(TIMEOUT);
        client.setReadTimeout(TIMEOUT);
        Logging.debug("stacinfo: GET {0}", url);
        return client.connect();
    }

    /**
     * Error answer of a STAC endpoint, carrying the HTTP status code.
     */
    static class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;

        private final int code;

        HttpStatusException(int code, String message) {
            super(message);
            this.code = code;
        }

        int getCode() {
            return code;
        }
    }
}
