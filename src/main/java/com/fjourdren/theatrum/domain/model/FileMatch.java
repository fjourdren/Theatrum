package com.fjourdren.theatrum.domain.model;

import java.nio.file.Path;
import java.util.Map;

/**
 * A file found by {@link StoragePort#searchFiles}, with the values captured from the
 * pattern's {@code {placeholders}} (plus a {@code FILENAME} entry).
 */
public record FileMatch(Path path, Map<String, String> vars) {
}
