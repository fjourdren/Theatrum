package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import com.fjourdren.theatrum.application.port.in.ResolveChannelUseCase;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.HttpConstants;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Serves the M3U8 playlist listing every available stream. */
@RequiredArgsConstructor
@Slf4j
@Component
public class AllStreamsPlaylistHandler {

    private final ResolveChannelUseCase applicationService;

    public void handle(HttpServletResponse response) throws IOException {
        String content;
        try {
            content = applicationService.buildAllStreamsPlaylist();
        } catch (RuntimeException e) {
            log.error("Error building all streams playlist: {}", e.getMessage());
            HttpErrors.send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error building all streams playlist");
            return;
        }

        response.setContentType(HttpConstants.CONTENT_TYPE_M3U);
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(content);
    }
}
