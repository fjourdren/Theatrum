package com.fjourdren.theatrum.domain.model;

/**
 * @param bitrate audio bitrate, e.g. {@code "128k"}
 * @param codec   audio codec, e.g. {@code "aac"}
 */
public record Audio(String bitrate, String codec) {
}
