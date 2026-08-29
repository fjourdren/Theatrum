package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AllStreamsPlaylist;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.EncodeJob;
import com.fjourdren.theatrum.domain.model.FileMatch;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.domain.model.Server;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoUnencodedDetectorTest {

    private static final AppPaths PATHS = new AppPaths(Path.of("data"), Path.of("frontend"));

    @Mock
    private StoragePort storage;
    @Mock
    private EncodeJobQueue encodeQueue;

    private VideoUnencodedDetector detector(Map<String, Stream> channels) {
        PathTemplateService templateService = new PathTemplateService();
        ApplicationService appService = new ApplicationService(
                new LoadedConfiguration(
                        new Application("", new AllStreamsPlaylist(false, "")),
                        new Server(0, 0, null),
                        channels),
                storage, templateService, PATHS);
        return new VideoUnencodedDetector(appService, encodeQueue, storage, templateService, PATHS);
    }

    @Test
    void findsUnencodedVideosAndQueuesThem() throws IOException {
        when(storage.searchFiles(anyString(), any())).thenReturn(List.of(
                new FileMatch(Path.of("data/uploads/video.mp4"), Map.of("FILENAME", "video.mp4"))));
        when(encodeQueue.enqueue(any())).thenReturn(true);

        Stream stream = Stream.builder()
                .type(StreamType.VIDEO_UNENCODED)
                .path("encoded/{FILENAME}")
                .videoInputPath("uploads/{FILENAME}")
                .build();

        detector(Map.of("/videos/{filename}", stream)).detectAndQueueVideos();

        verify(storage).searchFiles(Path.of("data", "uploads/{FILENAME}").toString(),
                VideoConstants.VALID_VIDEO_EXTENSIONS);

        ArgumentCaptor<EncodeJob> job = ArgumentCaptor.forClass(EncodeJob.class);
        verify(encodeQueue).enqueue(job.capture());
        assertThat(job.getValue().inputStoragePath()).isEqualTo(Path.of("data/uploads/video.mp4"));
        assertThat(job.getValue().outputStoragePath()).isEqualTo(Path.of("data/encoded/video.mp4/video.mp4"));
        assertThat(job.getValue().channel()).isEqualTo(stream);
    }

    @Test
    void skipsNonUnencodedStreamsWithoutAnInputPath() {
        detector(Map.of("/live/{username}", Stream.builder()
                .type(StreamType.LIVE).path("live/{username}").build())).detectAndQueueVideos();

        verifyNoInteractions(storage, encodeQueue);
    }

    /**
     * Faithful port of the Go guard {@code type != VIDEO_UNENCODED && videoInputPath == ""}:
     * a non-unencoded stream that does declare an input path is still scanned.
     */
    @Test
    void scansAnyStreamThatDeclaresAnInputPath() throws IOException {
        when(storage.searchFiles(anyString(), any())).thenReturn(List.of());

        detector(Map.of("/live/{username}", Stream.builder()
                .type(StreamType.LIVE).path("live/{username}").videoInputPath("uploads/{FILENAME}").build()))
                .detectAndQueueVideos();

        verify(storage).searchFiles(anyString(), any());
    }

    @Test
    void handlesSearchErrorsGracefully() throws IOException {
        when(storage.searchFiles(anyString(), any())).thenThrow(new IOException("disk error"));

        detector(Map.of("/videos/{filename}", Stream.builder()
                .type(StreamType.VIDEO_UNENCODED)
                .path("encoded/{FILENAME}")
                .videoInputPath("uploads/{FILENAME}")
                .build())).detectAndQueueVideos();

        verify(encodeQueue, never()).enqueue(any());
    }

    @Test
    void skipsMatchesWhoseOutputPathCannotBeTemplated() throws IOException {
        // "../etc" cannot be sanitized, so the output path resolution fails for this match.
        when(storage.searchFiles(anyString(), any())).thenReturn(List.of(
                new FileMatch(Path.of("data/uploads/x.mp4"), Map.of("FILENAME", "../etc"))));

        detector(Map.of("/videos/{filename}", Stream.builder()
                .type(StreamType.VIDEO_UNENCODED)
                .path("encoded/{FILENAME}")
                .videoInputPath("uploads/{FILENAME}")
                .build())).detectAndQueueVideos();

        verify(encodeQueue, never()).enqueue(any());
    }

    @Test
    void enqueueRefusalIsNotFatal() throws IOException {
        when(storage.searchFiles(anyString(), any())).thenReturn(List.of(
                new FileMatch(Path.of("data/uploads/video.mp4"), Map.of("FILENAME", "video.mp4"))));
        when(encodeQueue.enqueue(any())).thenReturn(false);

        detector(Map.of("/videos/{filename}", Stream.builder()
                .type(StreamType.VIDEO_UNENCODED)
                .path("encoded/{FILENAME}")
                .videoInputPath("uploads/{FILENAME}")
                .build())).detectAndQueueVideos();

        verify(encodeQueue).enqueue(any());
    }
}
