package com.fjourdren.theatrum.application.port.in;

import java.util.Map;

/** The path-template engine: {@code {username}} user variables and {@code {%UUID%}} builtins. */
public interface PathTemplateUseCase {

    /** Captures the user variables {@code input} supplies for {@code template}. */
    Map<String, String> extractValues(String template, String input);

    boolean matchesTemplate(String template, String input);

    /** Substitutes builtins first, then the supplied user variables. */
    String replacePlaceholders(String text, Map<String, String> vars);

    /** Evaluates the builtin functions {@code template} references, once. */
    Map<String, String> generateBuiltinVars(String template);
}
