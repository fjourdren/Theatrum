package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.AuthorizePublishUseCase;
import com.fjourdren.theatrum.application.port.in.exception.AuthenticationException;
import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.model.ChannelMatch;
import com.fjourdren.theatrum.domain.model.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** RTMP authentication and URL pattern matching. */
@Component
@RequiredArgsConstructor
public class RtmpAuthService implements AuthorizePublishUseCase {

    /** A {@code {var}} placeholder in a channel pattern or auth token template. */
    private final Map<String, Stream> channels;
    private final PathTemplateService templateService;

    /**
     * The channel map is a config value, not a bean, so it is read off {@link ApplicationService}
     * rather than injected. Configuration is immutable once loaded, so snapshotting it is safe.
     */
    @Autowired
    public RtmpAuthService(ApplicationService applicationService, PathTemplateService templateService) {
        this(applicationService.getChannels(), templateService);
    }

    /** Whether the TCURL matches any configured channel pattern. */
    @Override
    public boolean isAuthorized(String tcUrl) {
        return extractChannel(tcUrl).isPresent();
    }

    /**
     * Matches the TCURL against the configured channels, returning the first match in
     * configuration order. At most one pattern is expected to match.
     */
    @Override
    public Optional<ChannelMatch> extractChannel(String tcUrl) {
        var path = extractPathFromTcUrl(tcUrl);

        for (var channel : channels.entrySet()) {
            var vars = extractVariables(patternToRegex(channel.getKey()), path);
            if (vars.isPresent()) {
                return Optional.of(new ChannelMatch(channel.getValue(), vars.get(), channel.getKey()));
            }
        }
        return Optional.empty();
    }

    /**
     * Validates the publishing token, which must equal {@code XOR(live_stream_key, auth_input)}
     * hex-encoded, where the auth input is the channel's {@code auth_token_template} with its
     * {@code {var}} placeholders filled from the URL.
     *
     * @throws AuthenticationException if the token is absent, unresolvable or wrong
     */
    @Override
    public void validateAuthentication(Stream stream, Map<String, String> vars, String publishingName) {
        if (publishingName == null || publishingName.isEmpty()) {
            throw new AuthenticationException("empty publishingName provided");
        }

        var expectedToken = xorString(stream.liveStreamKey(), buildAuthInput(stream.authTokenTemplate(), vars));
        if (!publishingName.equals(expectedToken)) {
            throw new AuthenticationException("invalid authentication token");
        }
    }

    /** Resolves the stream's output path template against the URL variables. */
    @Override
    public String buildStreamPath(Stream stream, Map<String, String> vars) {
        return templateService.replacePlaceholders(stream.path(), vars);
    }

    /** Replaces every {@code {var}} in the template, failing with the full list of missing names. */
    private String buildAuthInput(String template, Map<String, String> vars) {
        var missing = new ArrayList<String>();
        var matcher = TemplateConstants.IDENTIFIER_VAR_REGEX.matcher(template);
        var result = new StringBuilder();

        while (matcher.find()) {
            var value = vars.get(matcher.group(1));
            if (value == null) {
                missing.add(matcher.group(1));
                value = matcher.group();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        if (!missing.isEmpty()) {
            throw new AuthenticationException("authentication failed: missing variables in URL: " + missing);
        }
        return result.toString();
    }

    /** XORs the input against the repeating live stream key and hex-encodes the result. */
    String xorString(String liveStreamKey, String input) {
        var key = liveStreamKey.getBytes(StandardCharsets.UTF_8);
        if (key.length == 0) {
            // Go divided by len(key) and panicked here; failing authentication is the safe port.
            throw new AuthenticationException("authentication failed: no live_stream_key configured");
        }

        var result = input.getBytes(StandardCharsets.UTF_8);
        for (var i = 0; i < result.length; i++) {
            result[i] ^= key[i % key.length];
        }
        return HexFormat.of().formatHex(result);
    }

    /**
     * Compiles a channel pattern into a regex, turning each {@code {var}} into a path-segment
     * capture group. Groups are positional rather than named: Java named groups reject the
     * underscores that channel variables such as {@code {room_id}} use.
     */
    CompiledPattern patternToRegex(String pattern) {
        var varNames = new ArrayList<String>();
        var regex = new StringBuilder();
        var matcher = TemplateConstants.IDENTIFIER_VAR_REGEX.matcher(pattern);
        var last = 0;

        while (matcher.find()) {
            regex.append(Pattern.quote(pattern.substring(last, matcher.start()))).append(TemplateConstants.VAR_SEGMENT_CAPTURE);
            varNames.add(matcher.group(1));
            last = matcher.end();
        }
        regex.append(Pattern.quote(pattern.substring(last)));

        return new CompiledPattern(Pattern.compile(regex.toString()), varNames);
    }

    /** Matches a path against a compiled pattern, zipping the capture groups onto the variable names. */
    Optional<Map<String, String>> extractVariables(CompiledPattern compiled, String path) {
        var matcher = compiled.regex().matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        var vars = new LinkedHashMap<String, String>();
        for (var i = 0; i < compiled.varNames().size(); i++) {
            vars.put(compiled.varNames().get(i), matcher.group(i + 1));
        }
        return Optional.of(vars);
    }

    /** The path component of a TCURL, or the raw input when it does not parse. */
    String extractPathFromTcUrl(String tcUrl) {
        try {
            var path = new URI(tcUrl).getPath();
            return path == null ? "" : path;
        } catch (URISyntaxException e) {
            return tcUrl;
        }
    }

    /** A channel pattern compiled to a regex, with its capture groups' variable names in order. */
    record CompiledPattern(Pattern regex, List<String> varNames) {
    }
}
