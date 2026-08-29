package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp;

import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Rtmp;
import com.fjourdren.theatrum.domain.model.Server;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.domain.service.LiveStreamRegistry;
import com.fjourdren.theatrum.domain.service.PathTemplateService;
import com.fjourdren.theatrum.domain.service.RtmpAuthService;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.management.StreamManager;
import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RtmpServerTest {

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    @Test
    void bindsAcceptsAConnectionAndShutsDown() throws Exception {
        int port = freePort();
        ApplicationService appService = mock(ApplicationService.class);
        when(appService.getServer()).thenReturn(new Server(8080, port, new Rtmp(1, 1)));
        PathTemplateService templateService = new PathTemplateService();
        Metrics metrics = new Metrics(new SimpleMeterRegistry());

        RtmpServer server = new RtmpServer(appService, new RtmpAuthService(Map.of(), templateService),
                templateService, new LiveStreamRegistry(), mock(StreamManager.class), metrics,
                new AppPaths(Path.of("data"), Path.of("frontend")));

        server.startRtmpServer();
        try {
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                assertThat(client.isConnected()).isTrue();
            }
            assertThat(server.getActiveStreams()).isEmpty();
        } finally {
            server.shutdownRtmpServer();
        }

        assertThatExceptionOfType(ConnectException.class)
                .isThrownBy(() -> new Socket(InetAddress.getLoopbackAddress(), port).close());
    }
}
