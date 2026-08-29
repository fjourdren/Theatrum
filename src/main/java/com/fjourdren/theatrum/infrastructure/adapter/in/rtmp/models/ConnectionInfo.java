package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.models;

import com.fjourdren.theatrum.domain.model.Stream;

import java.util.Map;
import java.util.Optional;

/**
 * Connection details captured from an RTMP connect command.
 *
 * @param vars variables extracted from the TCURL by channel pattern matching
 */
public record ConnectionInfo(String app, String tcUrl, Stream stream, Map<String, String> vars) {

    public ConnectionInfo {
        vars = vars == null ? Map.of() : Map.copyOf(vars);
    }

    public Optional<String> getVar(String key) {
        return Optional.ofNullable(vars.get(key));
    }

    /** All extracted variables. The returned map is immutable. */
    public Map<String, String> getVars() {
        return vars;
    }

    public Optional<String> getUsername() {
        return getVar("username");
    }

    public Optional<String> getHost() {
        return getVar("host");
    }

    public Optional<String> getAppName() {
        return getVar("app");
    }
}
