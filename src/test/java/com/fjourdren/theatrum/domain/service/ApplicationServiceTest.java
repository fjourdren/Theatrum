package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.model.AllStreamsPlaylist;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.FileMatch;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.domain.model.Server;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    private static final AppPaths PATHS = new AppPaths(Path.of("data"), Path.of("frontend"));

    @Mock
    private StoragePort storage;

    private static Application application(String publicPath, boolean playlistEnabled) {
        return new Application(publicPath, new AllStreamsPlaylist(playlistEnabled, "/all.m3u8"));
    }

    private ApplicationService service(Application app, Server server, Map<String, Stream> channels) {
        return new ApplicationService(new LoadedConfiguration(app, server, channels), storage,
                new PathTemplateService(), PATHS);
    }

    @Test
    void getApplicationReturnsTheConfiguredApplication() {
        Application app = application("http://localhost:8080", false);

        Application got = service(app, new Server(8080, 0, null), Map.of()).getApplication();

        assertThat(got).isSameAs(app);
        assertThat(got.publicPath()).isEqualTo("http://localhost:8080");
    }

    @Test
    void getServerReturnsTheConfiguredServer() {
        Server server = new Server(8080, 1935, null);

        Server got = service(application("", false), server, Map.of()).getServer();

        assertThat(got).isSameAs(server);
        assertThat(got.httpPort()).isEqualTo(8080);
    }

    @Test
    void getChannelsReturnsEveryChannel() {
        Map<String, Stream> channels = Map.of(
                "/user/{username}", Stream.builder().type(StreamType.LIVE).build());

        assertThat(service(application("", false), new Server(0, 0, null), channels).getChannels())
                .hasSize(1);
    }

    @Nested
    class GetChannel {

        private final Map<String, Stream> channels = Map.of("/user/{username}",
                Stream.builder().type(StreamType.LIVE).path("live/{username}").build());

        @Test
        void found() {
            Stream channel = service(application("", false), new Server(0, 0, null), channels)
                    .getChannel("/user/{username}");

            assertThat(channel.type()).isEqualTo(StreamType.LIVE);
        }

        @Test
        void notFound() {
            ApplicationService service = service(application("", false), new Server(0, 0, null), channels);

            assertThatThrownBy(() -> service.getChannel("/nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("channel not found: /nonexistent");
        }
    }

    @Nested
    class BuildAllStreamsPlaylist {

        @Test
        void disabledThrows() {
            ApplicationService service =
                    service(application("", false), new Server(0, 0, null), Map.of());

            assertThatThrownBy(service::buildAllStreamsPlaylist)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("all streams playlist is not enabled");
        }

        @Test
        void enabledBuildsM3u8() throws IOException {
            Map<String, Stream> channels = new LinkedHashMap<>();
            channels.put("/user/{username}", Stream.builder()
                    .type(StreamType.VIDEO_ENCODED).path("videos/{username}").build());
            when(storage.searchFiles(anyString(), any())).thenReturn(List.of(
                    new FileMatch(Path.of("videos/alice/master.m3u8"), Map.of("username", "alice"))));

            String result = service(application("http://localhost:8080", true),
                    new Server(0, 0, null), channels).buildAllStreamsPlaylist();

            assertThat(result)
                    .contains("#EXTM3U")
                    .contains("#EXT-X-VERSION:3")
                    .contains("#EXT-X-STREAM-INF:BANDWIDTH=0")
                    .contains("http://localhost:8080/user/alice/master.m3u8");
        }

        @Test
        void searchesUnderTheVideoDirectory() throws IOException {
            Map<String, Stream> channels = Map.of("/user/{username}", Stream.builder()
                    .type(StreamType.VIDEO_ENCODED).path("videos/{username}").build());
            when(storage.searchFiles(anyString(), any())).thenReturn(List.of());

            service(application("http://localhost:8080", true), new Server(0, 0, null), channels)
                    .buildAllStreamsPlaylist();

            org.mockito.Mockito.verify(storage).searchFiles(
                    Path.of("data", "videos/{username}", "master.m3u8").toString(),
                    com.fjourdren.theatrum.domain.constant.VideoConstants.VALID_MASTER_PLAYLIST_EXTENSIONS);
        }

        @Test
        void storageSearchErrorIsHandledGracefully() throws IOException {
            Map<String, Stream> channels = Map.of("/user/{username}", Stream.builder()
                    .type(StreamType.VIDEO_ENCODED).path("videos/{username}").build());
            when(storage.searchFiles(anyString(), any())).thenThrow(new IOException("disk error"));

            String result = service(application("http://localhost:8080", true),
                    new Server(0, 0, null), channels).buildAllStreamsPlaylist();

            assertThat(result).contains("#EXTM3U").doesNotContain("master.m3u8");
        }

        @Test
        void templateFailureSkipsTheEntry() throws IOException {
            Map<String, Stream> channels = Map.of("/user/{username}", Stream.builder()
                    .type(StreamType.VIDEO_ENCODED).path("videos/{username}").build());
            // "../etc" cannot be sanitized, so placeholder replacement fails for this match.
            when(storage.searchFiles(anyString(), any())).thenReturn(List.of(
                    new FileMatch(Path.of("videos/x/master.m3u8"), Map.of("username", "../etc"))));

            String result = service(application("http://localhost:8080", true),
                    new Server(0, 0, null), channels).buildAllStreamsPlaylist();

            assertThat(result).contains("#EXTM3U").doesNotContain("master.m3u8");
        }
    }
}
