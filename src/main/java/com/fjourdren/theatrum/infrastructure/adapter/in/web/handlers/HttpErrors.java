package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import com.fjourdren.theatrum.infrastructure.adapter.in.web.HttpConstants;
import jakarta.servlet.http.HttpServletResponse;
import lombok.experimental.UtilityClass;

import java.io.IOException;

/**
 * Error replies that mirror Go's {@code http.Error}: status, plain-text body, done.
 *
 * <p>{@code HttpServletResponse.sendError} cannot be used here. These handlers set a media type
 * such as {@code application/vnd.apple.mpegurl} before they know whether the file exists, and
 * {@code sendError} hands the response to Spring's error dispatch, which then fails to serialise
 * its error attributes with that preset content type and turns a 404 into a 500.
 */
@UtilityClass
final class HttpErrors {

    static void send(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(HttpConstants.CONTENT_TYPE_ERROR);
        response.setHeader(HttpConstants.HEADER_X_CONTENT_TYPE_OPTIONS, HttpConstants.NOSNIFF);
        response.getWriter().write(message + "\n");
    }
}
