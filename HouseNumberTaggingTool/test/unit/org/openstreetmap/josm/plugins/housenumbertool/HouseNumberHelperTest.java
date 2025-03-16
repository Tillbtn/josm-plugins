// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.housenumbertool;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link TagDialog}.
 */
class HouseNumberHelperTest {

    /**
     * Unit test of {@link HouseNumberHelper#incrementHouseNumber}
     */
    @Test
    void testIncrementHouseNumber() {
        assertEquals("2", HouseNumberHelper.incrementHouseNumber("1", 1, true));
        assertEquals("12", HouseNumberHelper.incrementHouseNumber("10", 2, true));
        assertEquals("2A", HouseNumberHelper.incrementHouseNumber("1A", 1, true));
        assertEquals("E2", HouseNumberHelper.incrementHouseNumber("E1", 1, true));
        //assertEquals("۲", HouseNumberHelper.incrementHouseNumber("۱", 1)); // FIXME: how to increment persian numbers ?
        assertEquals("2", HouseNumberHelper.incrementHouseNumber("۱", 1, true));
        assertNull(HouseNumberHelper.incrementHouseNumber(null, 1, true));
    }
}
