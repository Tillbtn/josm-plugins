// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.housenumbertool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.tools.Logging;

/**
 * A helper class for incrementing addr:housenumber
 */
public final class HouseNumberHelper {
    private HouseNumberHelper() {
        // Hide constructor
    }

    /**
     * Increment a house number
     * @param number The number to increment
     * @param increment The amount to increment the number by
     * @return The incremented number
     */
    public static String incrementHouseNumber(String number, int increment, boolean incrementNum) {
        if (number != null) {
            try {
                Matcher m = Pattern.compile("([^\\pN]+)?(\\pN+)([-/ ])?([^\\pN]+)?(\\pN+)?").matcher(number);
                if (m.matches()) {
                    if (incrementNum) {
                        String prefix = m.group(1) != null ? m.group(1) : "";
                        int n = Integer.parseInt(m.group(2)) + increment;
                        String preSuffix = m.group(3) != null ? m.group(3) : "";
                        String suffixLetter = m.group(4) != null ? m.group(4) : "";
                        String suffixNum = m.group(5) != null ? m.group(5) : "";
                        return prefix + n + preSuffix + suffixLetter + suffixNum;
                    }
                    else { // increment letter
                        String prefix = m.group(1) != null ? m.group(1) : "";
                        int n = Integer.parseInt(m.group(2));
                        // optional separator
                        String preSuffix = m.group(3) != null ? m.group(3) : "";
                        // optional letter
                        String suffixLetter = m.group(4) != null ? m.group(4) : "";
                        // optional number
                        String suffixNum = m.group(5) != null ? m.group(5) : "";
                        // optional letter: set to 'a' if there is no suffix yet
                        if (suffixLetter.isEmpty() && suffixNum.isEmpty()) suffixLetter = "a";
                        else if (!suffixLetter.isEmpty()){
                            int charValue = suffixLetter.charAt(0) ;
                            suffixLetter = String.valueOf( (char) (charValue + 1));
                        }
                        // optional number (should only exist if there is a separator and no letter)
                        if (!suffixNum.isEmpty()){
                            int newNum = Integer.parseInt(suffixNum);
                            newNum ++;
                            suffixNum = Integer.toString(newNum);
                        }
                        return prefix + n + preSuffix + suffixLetter + suffixNum;
                    }
                }
            } catch (NumberFormatException e) {
                Logging.trace(e);
            }
        }
        return null;
    }

    public static boolean hasLetter(String number) {
        if (number != null) {
            Matcher m = Pattern.compile("([^\\pN]+)?(\\pN+)([-/ ])?([^\\pN]+)?(\\pN+)?").matcher(number);
            if (m.matches()) {
                String suffixLetter = m.group(4) != null ? m.group(4) : "";
                return !suffixLetter.isEmpty();
            }
        }
        return false;
    }

    public static String incrementNumberRemoveLetter(String number, int increment) {
        if (number != null) {
            try {
                Matcher m = Pattern.compile("([^\\pN]+)?(\\pN+)([-/ ])?([^\\pN]+)?(\\pN+)?").matcher(number);
                if (m.matches()) {
                    String prefix = m.group(1) != null ? m.group(1) : "";
                    int n = Integer.parseInt(m.group(2)) + increment;
                    return prefix + n;
                }
            } catch (NumberFormatException e) {
                Logging.trace(e);
            }
        }
        return null;
    }
}
