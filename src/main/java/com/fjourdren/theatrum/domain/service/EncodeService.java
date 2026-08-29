package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.out.EncoderPort;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EncodeService {

    private final EncoderPort encoder;

    /** Encodes a source file into every quality the channel declares. */
    public void encodeStream(Path inputStoragePath, Path outputStoragePath, Stream channel) throws IOException {
        if (channel.qualities().isEmpty()) {
            throw new IllegalArgumentException(
                    "stream has no qualities defined for encoding (path: " + channel.path() + ")");
        }

        encoder.encodeVideo(inputStoragePath, outputStoragePath, channel.qualities(), channel.distribution());
    }

    /** Encodes a source file into a single named quality. */
    public void encodeQuality(Path inputStoragePath, Path outputStoragePath, String qualityName,
                              Quality quality, Distribution distribution) throws IOException {
        String name = (qualityName == null || qualityName.isEmpty())
                ? VideoConstants.DEFAULT_QUALITY
                : qualityName;

        encoder.encodeVideo(inputStoragePath, outputStoragePath, Map.of(name, quality), distribution);
    }
}
