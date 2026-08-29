package com.fjourdren.theatrum.application.port.in;

import com.fjourdren.theatrum.application.port.in.exception.AuthenticationException;
import com.fjourdren.theatrum.domain.model.ChannelMatch;
import com.fjourdren.theatrum.domain.model.Stream;

import java.util.Map;
import java.util.Optional;

/** Authorises an incoming RTMP publish against the configured channels. */
public interface AuthorizePublishUseCase {

    /** Whether {@code tcUrl} matches any configured live or restream channel. */
    boolean isAuthorized(String tcUrl);

    /** The channel {@code tcUrl} resolves to, with its captured template variables. */
    Optional<ChannelMatch> extractChannel(String tcUrl);

    /**
     * Verifies {@code publishingName} against the channel's resolved auth token.
     *
     * @throws AuthenticationException if the publishing name does not match
     */
    void validateAuthentication(Stream stream, Map<String, String> vars, String publishingName);

    /** The stream's storage path with {@code vars} applied. */
    String buildStreamPath(Stream stream, Map<String, String> vars);
}
