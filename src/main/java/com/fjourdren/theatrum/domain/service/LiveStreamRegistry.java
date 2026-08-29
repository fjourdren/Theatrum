package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.LiveStreamVarsUseCase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores pre-resolved builtin template variables per stream session, so builtins like
 * {@code {%UUID%}} and {@code {%STARTING_DATE%}} are generated once at RTMP publish time and
 * reused consistently by HTTP handlers and on reconnection.
 */
@Component
public class LiveStreamRegistry implements LiveStreamVarsUseCase {

    private final Map<String, Map<String, String>> paths = new ConcurrentHashMap<>();

    /**
     * Atomically stores {@code builtinVars} for {@code key} if absent.
     *
     * @return the existing vars on reconnection, or the newly stored vars on first publish
     */
    @Override
    public Map<String, String> getOrRegister(String key, Map<String, String> builtinVars) {
        return paths.computeIfAbsent(key, k -> Map.copyOf(builtinVars));
    }

    /** Returns the stored builtin vars for a stream key, if any. */
    @Override
    public Optional<Map<String, String>> getBuiltinVars(String key) {
        return Optional.ofNullable(paths.get(key));
    }

    /** Removes the builtin vars for a stream key (called after stream cleanup). */
    @Override
    public void unregister(String key) {
        paths.remove(key);
    }
}
