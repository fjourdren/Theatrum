package com.fjourdren.theatrum.domain.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class UrlUtils {

    /** Joins URL parts while preserving the protocol. */
    public static String joinUrl(String base, String... parts) {
        var joined = new StringBuilder(base.replaceAll("/+$", ""));
        for (String part : parts) {
            String trimmed = part.replaceAll("^/+|/+$", "");
            if (!trimmed.isEmpty()) {
                joined.append('/').append(trimmed);
            }
        }
        return joined.toString();
    }
}
