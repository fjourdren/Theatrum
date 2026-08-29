package com.fjourdren.theatrum.application.port.in;

import com.fjourdren.theatrum.domain.model.Stream;

import java.nio.file.Path;
import java.util.Map;

/** Resolves where a stream's files live on disk, for a driving adapter serving a request. */
public interface ServeStreamUseCase {

    /**
     * Resolves the storage path of {@code stream} with {@code templatingVars} applied to its
     * path template. The quality sub-directory is appended only in HLS-only mode, and never
     * for root resources such as the master playlist, the DASH manifest or the thumbnail.
     */
    Path getStreamStoragePath(Stream stream, Map<String, String> templatingVars);
}
