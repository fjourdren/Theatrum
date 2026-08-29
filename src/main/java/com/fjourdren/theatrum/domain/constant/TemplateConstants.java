package com.fjourdren.theatrum.domain.constant;

import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * The two placeholder syntaxes a path template understands: {@code {var}}, whose value comes from
 * the incoming URL, and {@code {%FUNC%}}, whose value is generated at substitution time.
 */
@UtilityClass
public final class TemplateConstants {

    // ------------------------------------------------------------------ {var}

    public static final char VAR_OPEN = '{';
    public static final char VAR_CLOSE = '}';

    /**
     * Matches a variable under any name, e.g. {@code {username}}. A {@code {%FUNC%}} left over from
     * builtin substitution matches too, which is what turns it into a wildcard segment when a
     * template is compiled.
     */
    public static final Pattern ANY_VAR_REGEX = Pattern.compile("\\{([^{}]+)\\}");

    /**
     * Matches a variable whose name is an identifier, e.g. {@code {room_id}}. Stricter than
     * {@link #ANY_VAR_REGEX}: it is what compiles a channel or auth pattern into a regex, where
     * anything that is not a plain name would produce a route nobody can reach.
     */
    public static final Pattern IDENTIFIER_VAR_REGEX = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    /**
     * Matches every variable that is not a {@link #FUNC_CALL_REGEX} builtin, i.e. one whose value
     * can only come from an incoming URL (Go: {@code userVarRegex}). Looser than
     * {@link #IDENTIFIER_VAR_REGEX} on purpose: a validator has to reject the malformed names too,
     * not just the ones that would compile.
     */
    public static final Pattern NON_FUNC_VAR_REGEX = Pattern.compile("\\{([^%][^}]*)\\}");

    /** What a variable becomes when a template is compiled: a capture of one path segment. */
    public static final String VAR_SEGMENT_CAPTURE = "([^/]+)";

    // -------------------------------------------------------------- {%FUNC%}

    /** Matches a builtin function call, e.g. {@code {%STARTING_DATE%}}. */
    public static final Pattern FUNC_CALL_REGEX = Pattern.compile("\\{%([A-Z_]+)%\\}");

    public static final String FUNC_NAME_STARTING_DATE = "STARTING_DATE";
    public static final String FUNC_NAME_UUID = "UUID";

    /** Java equivalent of the Go layout {@code 2006-01-02_15-04-05}. */
    public static final String STARTING_DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss";

    // ------------------------------------------------- Reserved variable names

    /** Set by the router from the first resource segment, consumed when resolving the storage path. */
    public static final String VAR_NAME_QUALITY = "quality";

    /** Set by the router to whatever follows the channel pattern. */
    public static final String VAR_NAME_RESOURCE = "resource";

    /** Extracted from a matched path by the template service; used to name an encoded output. */
    public static final String VAR_NAME_FILENAME = "FILENAME";

    /** The {@code {quality}} placeholder as written in a template. */
    public static final String QUALITY_VAR = VAR_OPEN + VAR_NAME_QUALITY + VAR_CLOSE;

    /** The {@code {FILENAME}} placeholder as written in a template. */
    public static final String FILENAME_VAR = VAR_OPEN + VAR_NAME_FILENAME + VAR_CLOSE;
}
