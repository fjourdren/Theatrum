package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.model.AllStreamsPlaylist;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.FileMatch;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.domain.model.Server;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.domain.service.PathTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Port of Go's {@code allStreamsPlaylistHandler_test.go}. */
@ExtendWith(MockitoExtension.class)
class AllStreamsPlaylistHandlerTest {

    private static final AppPaths PATHS = new AppPaths(Path.of("data"), Path.of("frontend"));

    @Mock
    private StoragePort storage;

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private AllStreamsPlaylistHandler handler(Application app, Map<String, Stream> channels) {
        var applicationService = new ApplicationService(
                new LoadedConfiguration(app, new Server(8080, 1935, null), channels),
                storage, new PathTemplateService(), PATHS);
        return new AllStreamsPlaylistHandler(applicationService);
    }

    private static Map<String, Stream> vodChannel() {
        var channels = new LinkedHashMap<String, Stream>();
        channels.put("/vod/{name}", Stream.builder().type(StreamType.VIDEO_ENCODED).path("videos/{name}").build());
        return channels;
    }

    @Test
    void buildsThePlaylistWithTheCorrectContentType() throws IOException {
        when(storage.searchFiles(anyString(), any())).thenReturn(
                List.of(new FileMatch(Path.of("videos/test/master.m3u8"), Map.of("name", "test"))));

        handler(new Application("http://localhost:8080", new AllStreamsPlaylist(true, "/all.m3u8")), vodChannel())
                .handle(response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/x-mpegURL");
        assertThat(response.getContentAsString()).contains("#EXTM3U");
        assertThat(response.getContentAsString()).contains("http://localhost:8080/vod/test/master.m3u8");
    }

    @Test
    void returns500WhenTheFeatureIsDisabled() throws IOException {
        handler(new Application("", new AllStreamsPlaylist(false, "")), new LinkedHashMap<>())
                .handle(response);

        assertThat(response.getStatus()).isEqualTo(500);
    }

    /** Storage failures are logged inside the service, so a partial playlist is still returned. */
    @Test
    void returns200WithAPartialPlaylistWhenStorageFails() throws IOException {
        when(storage.searchFiles(anyString(), any())).thenThrow(new IOException("storage error"));

        handler(new Application("http://localhost:8080", new AllStreamsPlaylist(true, "/all.m3u8")), vodChannel())
                .handle(response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("#EXTM3U");
    }
}
