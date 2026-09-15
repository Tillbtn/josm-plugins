// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Filter of the item table and of the map view, entered as free text.
 * <p>
 * The text is split at spaces, an item has to match every part. A part is either
 * <ul>
 * <li>{@code text}: matches if id, date or any property of the item contains the text,</li>
 * <li>{@code key=text}: matches if a property whose name contains {@code key} contains the text,</li>
 * <li>{@code -text} or {@code -key=text}: matches if the part above does <i>not</i> match.</li>
 * </ul>
 * Everything is compared case insensitively, so {@code dop10} or {@code bodenpixelgroesse=10}
 * both reduce the DOP collection of the LGLN to the 10 cm images.
 */
public final class StacFilter {

    /** Filter that accepts every item. */
    public static final StacFilter EMPTY = new StacFilter("", Collections.emptyList());

    private final String text;
    private final List<Term> terms;

    private StacFilter(String text, List<Term> terms) {
        this.text = text;
        this.terms = terms;
    }

    /**
     * Parses a filter text.
     * @param text the text entered by the user, may be {@code null} or empty
     * @return the filter, {@link #EMPTY} if the text does not contain any condition
     */
    public static StacFilter parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return EMPTY;
        }
        List<Term> terms = new ArrayList<>();
        for (String part : text.trim().split("\\s+")) {
            Term term = Term.parse(part);
            if (term != null) {
                terms.add(term);
            }
        }
        return terms.isEmpty() ? EMPTY : new StacFilter(text, terms);
    }

    /**
     * Determines whether this filter accepts every item.
     * @return {@code true} if the filter does not contain any condition
     */
    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /**
     * Returns the text this filter has been parsed from.
     * @return the filter text
     */
    public String getText() {
        return text;
    }

    /**
     * Applies the filter to one item.
     * @param item the item to check
     * @return {@code true} if the item matches all parts of the filter
     */
    public boolean matches(StacItem item) {
        for (Term term : terms) {
            if (term.matches(item) == term.negated) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the items matching this filter.
     * @param items the items to filter
     * @return the matching items, in the order of the input
     */
    public List<StacItem> filter(List<StacItem> items) {
        if (isEmpty()) {
            return items;
        }
        List<StacItem> result = new ArrayList<>();
        for (StacItem item : items) {
            if (matches(item)) {
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return text;
    }

    /**
     * One condition of a filter.
     */
    private static final class Term {

        private final boolean negated;
        private final String key;
        private final String value;

        private Term(boolean negated, String key, String value) {
            this.negated = negated;
            this.key = key;
            this.value = value;
        }

        static Term parse(String part) {
            String rest = part;
            boolean negated = rest.startsWith("-");
            if (negated) {
                rest = rest.substring(1);
            }
            int separator = rest.indexOf('=');
            String key = null;
            if (separator > 0) {
                key = rest.substring(0, separator).toLowerCase(Locale.ROOT);
                rest = rest.substring(separator + 1);
            }
            if (rest.isEmpty()) {
                return null;
            }
            return new Term(negated, key, rest.toLowerCase(Locale.ROOT));
        }

        boolean matches(StacItem item) {
            if (key != null) {
                for (Map.Entry<String, String> property : item.getProperties().entrySet()) {
                    if (property.getKey().toLowerCase(Locale.ROOT).contains(key) && contains(property.getValue())) {
                        return true;
                    }
                }
                return false;
            }
            if (contains(item.getId()) || contains(item.getDateLabel()) || contains(item.getCollection())) {
                return true;
            }
            return item.getProperties().values().stream().anyMatch(this::contains);
        }

        private boolean contains(String candidate) {
            return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(value);
        }
    }
}
