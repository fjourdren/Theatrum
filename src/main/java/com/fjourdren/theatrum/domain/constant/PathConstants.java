package com.fjourdren.theatrum.domain.constant;

import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * What a path must not contain before it is allowed near the file system.
 *
 * <p>The two lists deliberately differ, as they did in Go: a configured path is validated once at
 * boot and may legitimately mention a home directory, while a glob pattern is built from request
 * data at serve time and gets the stricter list. Keeping them side by side makes the difference a
 * decision someone can review, rather than a discrepancy hidden in two adapters.
 */
@UtilityClass
public final class PathConstants {

    /** The parent-directory segment: never valid in a template value or a normalised path. */
    public static final String PARENT_DIR = "..";

    /** Rejected in a configured {@code path} (config adapter, checked at boot). */
    public static final List<String> DANGEROUS_IN_CONFIG_PATH =
            List.of("%00", "%2e", "%2f", "%5c", "|", ">", "<", "*", "?");

    /** Rejected in a storage glob pattern (persistence adapter); also bars {@code ~}. */
    public static final List<String> DANGEROUS_IN_GLOB =
            List.of("%00", "%2e", "%2f", "%5c", "~", "|", ">", "<", "*", "?");
}
