package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.infrastructure.adapter.in.web.HttpConstants;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Serves the static frontend files. */
@RequiredArgsConstructor
@Component
public class FrontendHandler {

    private static final String INDEX_FILE = "index.html";

    private final AppPaths appPaths;

    public void handle(String requestPath, HttpServletResponse response) throws IOException {
        switch (extension(requestPath)) {
            // HTML may be updated, so cache it only briefly.
            case ".html" -> response.setHeader(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_TEN_MINUTES);
            // JS and CSS change less frequently.
            case ".js", ".css" -> response.setHeader(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_ONE_DAY);
            // Images rarely change at all.
            case VideoConstants.EXT_THUMBNAIL, ".jpg", ".jpeg", ".gif", ".svg", ".ico" ->
                    response.setHeader(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_ONE_YEAR);
            default -> {
                response.setHeader(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_NONE);
                response.setHeader(HttpConstants.HEADER_PRAGMA, HttpConstants.NO_CACHE);
                response.setHeader(HttpConstants.HEADER_EXPIRES, HttpConstants.EXPIRES_IMMEDIATELY);
            }
        }

        Path root = appPaths.frontendDir().toAbsolutePath().normalize();
        Path file;
        try {
            file = root.resolve(trimLeadingSlash(requestPath)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
            return;
        }

        // The path comes straight from the URL: keep it inside the frontend directory.
        if (!file.startsWith(root)) {
            HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
            return;
        }
        if (Files.isDirectory(file)) {
            file = file.resolve(INDEX_FILE);
        }
        if (!Files.isRegularFile(file)) {
            HttpErrors.send(response, HttpServletResponse.SC_NOT_FOUND, HttpConstants.NOT_FOUND_MESSAGE);
            return;
        }

        MediaTypeFactory.getMediaType(file.getFileName().toString())
                .ifPresent(type -> response.setContentType(type.toString()));
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
    }

    private static String trimLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** Port of Go's {@code filepath.Ext}: the final dot and everything after it, or an empty string. */
    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash ? path.substring(dot) : "";
    }
}
