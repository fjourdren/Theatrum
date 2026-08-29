package com.fjourdren.theatrum.infrastructure.adapter.in.web;

import com.fjourdren.theatrum.application.port.in.ResolveChannelUseCase;
import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers.AllStreamsPlaylistHandler;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers.FrontendHandler;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers.StreamRequestHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Single entry point for every request that is not {@code /metrics}.
 *
 * <p>Go registered a gorilla/mux subrouter per configured channel at startup. Spring's annotation
 * routing is static, so the channel patterns are compiled to regexes here and matched by hand, in
 * configuration order. The four route shapes gorilla declared per channel collapse into one rule:
 * everything after the channel pattern is the resource, and a resource of two or more segments has
 * its first segment taken as the quality.
 */
@Slf4j
@RestController
public class StreamController {

    private final StreamRequestHandler streamRequestHandler;
    private final AllStreamsPlaylistHandler allStreamsPlaylistHandler;
    private final FrontendHandler frontendHandler;

    private final List<CompiledChannel> channels = new ArrayList<>();
    private final String allStreamsPlaylistPath;

    public StreamController(ResolveChannelUseCase applicationService, StreamRequestHandler streamRequestHandler,
                            AllStreamsPlaylistHandler allStreamsPlaylistHandler, FrontendHandler frontendHandler) {
        this.streamRequestHandler = streamRequestHandler;
        this.allStreamsPlaylistHandler = allStreamsPlaylistHandler;
        this.frontendHandler = frontendHandler;

        var playlist = applicationService.getApplication().allStreamsPlaylist();
        if (playlist.enabled()) {
            allStreamsPlaylistPath = playlist.path().startsWith("/") ? playlist.path() : "/" + playlist.path();
            log.info("Registering all streams playlist at: {}", allStreamsPlaylistPath);
        } else {
            allStreamsPlaylistPath = null;
        }

        applicationService.getChannels().forEach((pattern, channel) -> {
            log.info("Registering channel: {} -> {}", pattern, channel.path());
            channels.add(compile(pattern, channel));
        });
    }

    @RequestMapping("/**")
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (path.equals(allStreamsPlaylistPath)) {
            allStreamsPlaylistHandler.handle(response);
            return;
        }

        for (CompiledChannel channel : channels) {
            var matcher = channel.regex().matcher(path);
            if (!matcher.matches()) {
                continue;
            }

            var vars = new LinkedHashMap<String, String>();
            for (int i = 0; i < channel.varNames().size(); i++) {
                vars.put(channel.varNames().get(i), matcher.group(i + 1));
            }

            // The trailing group always starts with the separating slash.
            String resource = matcher.group(matcher.groupCount()).substring(1);
            int slash = resource.indexOf('/');
            if (slash >= 0) {
                vars.put(TemplateConstants.VAR_NAME_QUALITY, resource.substring(0, slash));
                resource = resource.substring(slash + 1);
            }
            vars.put(TemplateConstants.VAR_NAME_RESOURCE, resource);

            streamRequestHandler.handle(channel.stream(), vars, request, response);
            return;
        }

        frontendHandler.handle(path, response);
    }

    /**
     * Compiles a channel pattern into a regex whose groups are, in order, the pattern's
     * {@code {var}} values followed by the remainder of the path. Groups are positional rather
     * than named: Java named groups reject the underscores channel variables such as
     * {@code {room_id}} use.
     */
    private static CompiledChannel compile(String pattern, Stream stream) {
        var varNames = new ArrayList<String>();
        var regex = new StringBuilder();
        var matcher = TemplateConstants.IDENTIFIER_VAR_REGEX.matcher(pattern);
        int last = 0;

        while (matcher.find()) {
            regex.append(Pattern.quote(pattern.substring(last, matcher.start())))
                    .append(TemplateConstants.VAR_SEGMENT_CAPTURE);
            varNames.add(matcher.group(1));
            last = matcher.end();
        }
        regex.append(Pattern.quote(pattern.substring(last))).append("(/.*)");

        return new CompiledChannel(stream, Pattern.compile(regex.toString()), List.copyOf(varNames));
    }

    /** A configured channel with its pattern compiled, and its capture groups' variable names in order. */
    private record CompiledChannel(Stream stream, Pattern regex, List<String> varNames) {
    }
}
