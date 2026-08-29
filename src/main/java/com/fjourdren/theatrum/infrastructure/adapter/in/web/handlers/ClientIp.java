package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import lombok.experimental.UtilityClass;

/** Resolves the originating client address of an HTTP request. */
@UtilityClass
final class ClientIp {

    /**
     * Extracts the client IP, preferring the {@code X-Forwarded-For} header (first entry) so
     * reverse-proxied requests are attributed to the original client.
     */
    static String from(String xForwardedFor, String remoteAddr) {
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            int comma = xForwardedFor.indexOf(',');
            return (comma == -1 ? xForwardedFor : xForwardedFor.substring(0, comma)).trim();
        }

        // Strip the port; an address without one is returned unchanged.
        int colon = remoteAddr.lastIndexOf(':');
        if (colon == -1) {
            return remoteAddr;
        }
        String host = remoteAddr.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host.indexOf(':') == -1 ? host : remoteAddr; // bare IPv6, no port
    }
}
