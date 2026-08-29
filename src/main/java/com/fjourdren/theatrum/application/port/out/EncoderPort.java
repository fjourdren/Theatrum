package com.fjourdren.theatrum.application.port.out;

import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Quality;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public interface EncoderPort {

    /** Encodes a video file to the given qualities using the given distribution settings. */
    void encodeVideo(Path inputPath, Path outputPath, Map<String, Quality> qualities, Distribution distribution)
            throws IOException;
}
