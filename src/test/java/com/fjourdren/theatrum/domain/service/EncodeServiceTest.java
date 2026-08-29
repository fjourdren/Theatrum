package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.out.EncoderPort;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EncodeServiceTest {

    private static final Path INPUT = Path.of("input.mp4");
    private static final Path OUTPUT = Path.of("output/");

    @Mock
    private EncoderPort encoder;

    private EncodeService service() {
        return new EncodeService(encoder);
    }

    private static Quality quality(int width, int height) {
        return new Quality(width, height, 30, "1000k", "libx264", new Audio("128k", "aac"));
    }

    @Nested
    class EncodeStream {

        @Test
        void delegatesToTheEncoder() throws IOException {
            Map<String, Quality> qualities = new LinkedHashMap<>();
            qualities.put("low", quality(640, 360));
            qualities.put("high", quality(1920, 1080));
            Stream channel = Stream.builder()
                    .path("test/path")
                    .qualities(qualities)
                    .hls(new Hls(6, 3))
                    .build();

            service().encodeStream(INPUT, OUTPUT, channel);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Quality>> captured = ArgumentCaptor.forClass(Map.class);
            verify(encoder).encodeVideo(org.mockito.ArgumentMatchers.eq(INPUT),
                    org.mockito.ArgumentMatchers.eq(OUTPUT), captured.capture(),
                    org.mockito.ArgumentMatchers.eq(channel.distribution()));
            assertThat(captured.getValue()).containsOnlyKeys("low", "high");
        }

        @Test
        void failsWhenNoQualitiesAreDefined() throws IOException {
            Stream channel = Stream.builder().path("test/path").qualities(Map.of()).build();

            assertThatThrownBy(() -> service().encodeStream(INPUT, OUTPUT, channel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no qualities defined")
                    .hasMessageContaining("test/path");

            verify(encoder, never()).encodeVideo(any(), any(), any(), any());
        }

        @Test
        void propagatesEncoderFailures() throws IOException {
            doThrow(new IOException("ffmpeg crashed"))
                    .when(encoder).encodeVideo(any(), any(), any(), any());
            Stream channel = Stream.builder()
                    .path("test/path")
                    .quality("low", quality(640, 360))
                    .build();

            assertThatThrownBy(() -> service().encodeStream(INPUT, OUTPUT, channel))
                    .isInstanceOf(IOException.class)
                    .hasMessage("ffmpeg crashed");
        }
    }

    @Nested
    class EncodeQuality {

        @Test
        void wrapsTheQualityUnderItsName() throws IOException {
            Distribution distribution = Distribution.ofHls(new Hls(4, 3));

            service().encodeQuality(INPUT, OUTPUT, "medium", quality(1280, 720), distribution);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Quality>> captured = ArgumentCaptor.forClass(Map.class);
            verify(encoder).encodeVideo(org.mockito.ArgumentMatchers.eq(INPUT),
                    org.mockito.ArgumentMatchers.eq(OUTPUT), captured.capture(),
                    org.mockito.ArgumentMatchers.eq(distribution));
            assertThat(captured.getValue()).containsOnlyKeys("medium");
        }

        @Test
        void blankNameFallsBackToTheDefaultQuality() throws IOException {
            service().encodeQuality(INPUT, OUTPUT, "", quality(640, 360), Distribution.none());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Quality>> captured = ArgumentCaptor.forClass(Map.class);
            verify(encoder).encodeVideo(any(), any(), captured.capture(), any());
            assertThat(captured.getValue()).containsOnlyKeys(VideoConstants.DEFAULT_QUALITY);
        }

        @Test
        void propagatesEncoderFailures() throws IOException {
            doThrow(new IOException("encode failed"))
                    .when(encoder).encodeVideo(any(), any(), any(), any());

            assertThatThrownBy(() ->
                    service().encodeQuality(INPUT, OUTPUT, "low", quality(640, 360), Distribution.none()))
                    .isInstanceOf(IOException.class)
                    .hasMessage("encode failed");
        }
    }
}
