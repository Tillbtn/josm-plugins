# stacinfo

JOSM plugin that shows **how up to date a background imagery layer is**. For the selected layer it
queries the [STAC API](https://stacspec.org/) of the imagery provider and draws the footprints of the
images in the current map view, coloured and labelled by their acquisition date.

Providers without a STAC API often publish the same information as a WFS overview of their aerial
images, which the plugin reads as well — see [Adding a new endpoint](#adding-a-new-endpoint).

## Usage

* `Imagery` &rarr; `Imagery dates (STAC)` opens the dialog, creates the layer and loads the dates of
  the current map view.
* The dialog offers all configured endpoints. `Automatic` picks the endpoint that matches one of the
  background layers currently loaded (for example *Niedersachsen DOP20*).
* `Automatically` reloads the dates whenever the map view changes. Very large map views are not
  queried; the layer then asks you to zoom in.
* `Latest only` (on by default) shows the current state: of every place only the newest image is
  listed and drawn. Endpoints such as the DOP collection of the LGLN are archives that keep every
  flight since 2012, so without this filter the same place carries a stack of items of many years.
  Older items stay visible where they are not covered by a newer one, which is exactly the border of
  the last flight area. Switch it off (here or in the layer menu) to see the whole history of a place.
* The table lists the items with their date and the detail properties of the source (for DOP20 for
  example the name of the flight and the ground resolution `bodenpixelgroesse`, which separates the
  10 cm from the 20 cm product). Selecting a row highlights its footprint, a double click zooms to
  it, the tooltip of a row shows all properties.
* `Details` shows everything the endpoint knows about the selected item: every property, the bounding
  box and the files (assets) it offers. A double click on a URL opens it in the browser, `Copy` puts
  the whole list into the clipboard.
* The filter field restricts the table **and** the map view without querying the endpoint again:

  | Input | Effect |
  | --- | --- |
  | `dop10` | items whose id, date, collection or any property contains `dop10` |
  | `bodenpixelgroesse=20` | items whose property `bodenpixelgroesse` contains `20`; the property name may be abbreviated (`boden=20`) |
  | `-2012` | items *not* matching `2012` |
  | `bodenpixelgroesse=20 -2026` | both conditions, every part has to match |

  The filter is applied before `Latest only`, so `dop10` shows the most recent coverage of the 10 cm
  images.
* Colours run from pale (oldest image in the view) to the full source colour (newest image); the
  legend in the bottom left corner shows the date range. Footprints are drawn from the oldest to the
  newest item, so the current state is always on top.

## Adding a new endpoint

Everything the plugin knows about an endpoint is a small JSON object, so a new provider only needs a
name and a URL. There are two ways to add one:

1. **In JOSM**: `Preferences` &rarr; `Imagery` &rarr; `STAC dates` &rarr; `New`. `Test connection`
   asks the endpoint for its collections (a WFS for its feature types), which is a quick way to check
   a URL.
2. **In the configuration file** `stac_sources.json` in the plugin preferences directory
   (`<JOSM preferences>/plugins/stacinfo/`, the exact path is shown in the preferences). Press
   `Reload file` after editing it.

```json
{
  "sources": [
    {
      "name": "Niedersachsen DOP20 (LGLN)",
      "url": "https://dop.stac.lgln.niedersachsen.de/",
      "collections": ["DOP"],
      "layerMatch": ["Niedersachsen-DOP20", "niedersachsen.*dop"],
      "dateProperty": "datetime",
      "detailProperties": ["bildflugname", "bodenpixelgroesse"],
      "color": "#E8590C",
      "attribution": "© LGLN, CC BY 4.0"
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `name` | Display name, also the key used to override a bundled source. Required. |
| `type` | `stac` (default) or `wfs`, see [WFS sources](#wfs-sources). |
| `url` | Root URL of the STAC API (`/search` is appended) or a complete search URL. For a WFS the URL is used unchanged and the request parameters are appended. Required. |
| `collections` | Collections to query. Empty means all collections of the endpoint. For a WFS these are the feature types, at least one is required. |
| `layerMatch` | Regular expressions (case insensitive, matched as substrings) against name, id and URL of an imagery layer. If one matches, the source is used by `Automatic`. |
| `dateProperty` | Item property holding the acquisition date, `datetime` by default. `start_datetime`, `end_datetime` and `created` are used as fallbacks. |
| `detailProperties` | Item properties shown as additional columns in the dialog. |
| `color` | Colour of the footprints, for example `#E8590C`. |
| `attribution` | Shown in the layer information. |
| `query` | Server side item filter in the syntax of the [STAC query extension](https://github.com/stac-api-extensions/query), for example `{"bodenpixelgroesse": "20"}` for the 20 cm images or `{"eo:cloud_cover": {"lt": 10}}` for nearly cloudless scenes. A plain value is turned into an equality check. Use it to define a source that only ever returns one product. |
| `query` | Server side item filter in the syntax of the STAC query extension. STAC only. |
| `sortByDate` | Asks the server for the newest items first, `true` by default: `sortby=-properties.datetime` for a STAC API, `SORTBY=<dateProperty> D` for a WFS. It is switched off automatically for endpoints that reject the parameter. |
| `enabled` | Set to `false` to hide a source. |

The plugin ships with the endpoints of the LGLN (DOP20, bDOM20, DOM1, DGM1 and ALKIS of
Niedersachsen), the WFS of the LGB (DOP of Brandenburg, plus its finished flight cycles as a source
that is switched off by default) and Sentinel-2 L2A of Earth Search and of CODE-DE. The bundled list
and the user file are merged: an entry of the user file replaces the bundled entry of the same name,
so bundled sources can be changed or switched off without being lost when the plugin is updated. Only
entries that differ from a bundled source are written back to the user file.

### WFS sources

Of the German state surveying offices only the LGLN of Niedersachsen runs a STAC API. Several others
publish an equivalent *Aktualitätsübersicht* as a WFS, which returns the same thing the plugin needs:
GeoJSON features with a footprint and a date. `"type": "wfs"` switches a source over to
`GetFeature` requests:

```json
{
  "name": "Brandenburg DOP (LGB)",
  "type": "wfs",
  "url": "https://isk.geobasis-bb.de/ows/aktualitaeten_wfs",
  "collections": ["app:dop_single"],
  "layerMatch": ["Brandenburg-DOP20c", "geobasis-bb.*dop"],
  "dateProperty": "creationdate",
  "detailProperties": ["product", "sheetnr", "publicationdate"],
  "color": "#D6336C",
  "attribution": "© GeoBasis-DE/LGB, dl-de/by-2-0"
}
```

The plugin asks for WFS 2.0 with `OUTPUTFORMAT=application/geo+json` and
`SRSNAME=urn:ogc:def:crs:EPSG::4326`, so the service has to offer GeoJSON. Every feature type of
`collections` is requested on its own and the results are merged, which is how the source above lists
several flight cycles at once. Paging uses `STARTINDEX`, since a WFS has no `rel=next` links.

Feature types worth knowing, all in the same LGB service: `app:dop_single` is the current DOP,
`app:dop20rgbi_2022_2024_single` and its predecessors down to `app:dop50g_1992_1997_single` are the
finished cycles, and `app:dop10_all_years` carries the whole history of the 10 cm images with a
`creationdate_year` property. `Test connection` lists all 68 of them.

Similar services exist in Baden-Württemberg (*WFS LGL-BW ATKIS Digitale Orthophotos 10cm/20cm
Bildflugkacheln Aktualität*) and Sachsen-Anhalt (*WFS ST TrueDOP Kachelübersicht mit
Aktualitätsangaben*); they are not bundled because their URLs have not been confirmed yet.

## Requirements of the endpoint

For a WFS source, see [WFS sources](#wfs-sources). For a STAC source
the plugin uses the STAC API item search (`GET /search?bbox=...&limit=...&collections=...&sortby=-properties.datetime`)
and follows `rel=next` links, so any STAC API implementing item search over GET works; endpoints
without support for `sortby` are queried again without it. `query` is only sent if the source
configures it and needs the query extension of the endpoint. Items need a
`geometry` or a `bbox`; the geometry is expected in WGS84 as `Polygon` or `MultiPolygon`.

## Preferences

| Key | Default | Meaning |
| --- | --- | --- |
| `stacinfo.auto-refresh` | `true` | Reload when the map view changes |
| `stacinfo.latest-only` | `true` | Show only the most recent item of every place |
| `stacinfo.show-labels` | `true` | Draw the date into every footprint |
| `stacinfo.show-legend` | `true` | Draw the colour legend |
| `stacinfo.max-items` | `500` | Maximum number of items per query |
| `stacinfo.fill-alpha` | `45` | Opacity of the footprint fill |
| `stacinfo.max-area` | `0.15` | Largest map view (square degrees) that is queried |
| `stacinfo.refresh-delay` | `700` | Milliseconds between map view change and reload |

## Building

```sh
mvn -o package        # jar in target/stacinfo.jar
ant dist              # jar in ../../dist/stacinfo.jar
```
