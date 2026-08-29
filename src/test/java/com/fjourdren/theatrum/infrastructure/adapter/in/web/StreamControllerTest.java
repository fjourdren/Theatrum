package com.fjourdren.theatrum.infrastructure.adapter.in.web;

import com.fjourdren.theatrum.domain.model.AllStreamsPlaylist;
import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers.AllStreamsPlaylistHandler;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers.FrontendHandler;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers.StreamRequestHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing tests. Go delegated this to gorilla/mux subrouters registered per channel; Spring's
 * annotation routing is static, so the controller reproduces those four route shapes by hand.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamControllerTest {

    private static final Stream USER_CHANNEL = Stream.builder()
            .type(StreamType.LIVE).path("live/{username}").hls(new Hls(2, 3)).build();
    private static final Stream VOD_CHANNEL = Stream.builder()
            .type(StreamType.VIDEO_ENCODED).path("videos/{name}").build();

    @Mock
    private ApplicationService applicationService;
    @Mock
    private StreamRequestHandler streamRequestHandler;
    @Mock
    private AllStreamsPlaylistHandler allStreamsPlaylistHandler;
    @Mock
    private FrontendHandler frontendHandler;

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private StreamController controller(boolean playlistEnabled, String playlistPath) {
        var channels = new LinkedHashMap<String, Stream>();
        channels.put("/user/{username}", USER_CHANNEL);
        channels.put("/vod/{name}", VOD_CHANNEL);

        when(applicationService.getApplication())
                .thenReturn(new Application("*", new AllStreamsPlaylist(playlistEnabled, playlistPath)));
        when(applicationService.getChannels()).thenReturn(channels);

        return new StreamController(applicationService, streamRequestHandler, allStreamsPlaylistHandler,
                frontendHandler);
    }

    private StreamController controller() {
        return controller(false, "");
    }

    private void request(StreamController controller, String uri) throws IOException {
        controller.handle(new MockHttpServletRequest("GET", uri), response);
    }

    private Map<String, String> capturedVars() throws IOException {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(streamRequestHandler).handle(any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    void rootResourceRoutesToTheStreamHandlerWithoutQuality() throws IOException {
        request(controller(), "/user/alice/master.m3u8");

        verify(streamRequestHandler).handle(eq(USER_CHANNEL), any(), any(), any());
        assertThat(capturedVars()).containsExactlyInAnyOrderEntriesOf(
                Map.of("username", "alice", "resource", "master.m3u8"));
    }

    @Test
    void qualityPrefixedResourceSplitsOnTheFirstSegment() throws IOException {
        request(controller(), "/user/alice/low/playlist.m3u8");

        assertThat(capturedVars()).containsExactlyInAnyOrderEntriesOf(
                Map.of("username", "alice", "quality", "low", "resource", "playlist.m3u8"));
    }

    @Test
    void resourceWithoutQualityPrefixKeepsTheDefaultQuality() throws IOException {
        request(controller(), "/user/alice/playlist.m3u8");

        assertThat(capturedVars()).containsExactlyInAnyOrderEntriesOf(
                Map.of("username", "alice", "resource", "playlist.m3u8"));
    }

    @Test
    void deepResourcePathsKeepTheirRemainingSegments() throws IOException {
        request(controller(), "/user/alice/low/nested/segment_000.ts");

        assertThat(capturedVars()).containsExactlyInAnyOrderEntriesOf(
                Map.of("username", "alice", "quality", "low", "resource", "nested/segment_000.ts"));
    }

    @Test
    void trailingSlashDispatchesAnEmptyResource() throws IOException {
        request(controller(), "/user/alice/");

        assertThat(capturedVars()).containsExactlyInAnyOrderEntriesOf(
                Map.of("username", "alice", "resource", ""));
    }

    @Test
    void aChannelPathWithoutAResourceFallsThroughToTheFrontend() throws IOException {
        request(controller(), "/user/alice");

        verify(streamRequestHandler, never()).handle(any(), any(), any(), any());
        verify(frontendHandler).handle(eq("/user/alice"), any());
    }

    @Test
    void channelsAreMatchedInConfigurationOrder() throws IOException {
        request(controller(), "/vod/clip/master.m3u8");

        verify(streamRequestHandler).handle(eq(VOD_CHANNEL), any(), any(), any());
        assertThat(capturedVars()).containsExactlyInAnyOrderEntriesOf(
                Map.of("name", "clip", "resource", "master.m3u8"));
    }

    @Test
    void unmatchedPathsGoToTheFrontend() throws IOException {
        request(controller(), "/index.html");

        verify(frontendHandler).handle(eq("/index.html"), any());
        verify(streamRequestHandler, never()).handle(any(), any(), any(), any());
    }

    @Test
    void servesTheAllStreamsPlaylistAtItsConfiguredPath() throws IOException {
        request(controller(true, "all_streams.m3u8"), "/all_streams.m3u8");

        verify(allStreamsPlaylistHandler).handle(any());
        verify(frontendHandler, never()).handle(any(), any());
    }

    @Test
    void acceptsAConfiguredPlaylistPathWithALeadingSlash() throws IOException {
        request(controller(true, "/all_streams.m3u8"), "/all_streams.m3u8");

        verify(allStreamsPlaylistHandler).handle(any());
    }

    @Test
    void theAllStreamsPlaylistPathIsNotRegisteredWhenDisabled() throws IOException {
        request(controller(false, "all_streams.m3u8"), "/all_streams.m3u8");

        verify(allStreamsPlaylistHandler, never()).handle(any());
        verify(frontendHandler).handle(eq("/all_streams.m3u8"), any());
    }

    @Test
    void stripsTheServletContextPathBeforeMatching() throws IOException {
        var request = new MockHttpServletRequest("GET", "/app/user/alice/master.m3u8");
        request.setContextPath("/app");

        controller().handle(request, response);

        verify(streamRequestHandler).handle(eq(USER_CHANNEL), any(), any(), any());
    }
}
