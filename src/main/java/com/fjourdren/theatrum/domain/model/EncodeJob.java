package com.fjourdren.theatrum.domain.model;

import java.nio.file.Path;

/** A video encoding job. */
public record EncodeJob(Path inputStoragePath, Path outputStoragePath, Stream channel) {
}
