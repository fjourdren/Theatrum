package com.fjourdren.theatrum.application.port.in;

import java.util.Map;
import java.util.Optional;

/**
 * Keeps a live stream's builtin variables stable for its whole session, so {@code {%UUID%}} and
 * {@code {%STARTING_DATE%}} resolve identically on the RTMP and HTTP sides and across reconnects.
 */
public interface LiveStreamVarsUseCase {

    /** Returns the variables already registered for {@code key}, registering these if absent. */
    Map<String, String> getOrRegister(String key, Map<String, String> builtinVars);

    Optional<Map<String, String>> getBuiltinVars(String key);

    void unregister(String key);
}
