package com.fjourdren.theatrum.domain.model;

import java.util.Map;

/**
 * A TCURL matched against a configured channel.
 *
 * @param stream  the matched channel configuration
 * @param vars    the {@code {var}} values extracted from the URL
 * @param pattern the channel pattern that matched
 */
public record ChannelMatch(Stream stream, Map<String, String> vars, String pattern) {
}
