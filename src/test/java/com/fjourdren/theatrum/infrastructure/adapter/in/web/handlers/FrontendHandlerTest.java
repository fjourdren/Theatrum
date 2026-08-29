package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import com.fjourdren.theatrum.domain.model.AppPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of Go's {@code frontendHandler_test.go}. */
class FrontendHandlerTest {

    @TempDir
    Path tempDir;

    private FrontendHandler handler;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() throws IOException {
        Path frontendDir = tempDir.resolve("frontend");
        Files.createDirectories(frontendDir);
        Files.writeString(frontendDir.resolve("index.html"), "<html></html>");
        Files.writeString(frontendDir.resolve("app.js"), "console.log('hello')");
        Files.writeString(frontendDir.resolve("style.css"), "body {}");
        Files.writeString(frontendDir.resolve("logo.png"), "PNG");
        Files.writeString(frontendDir.resolve("favicon.ico"), "ICO");
        Files.writeString(frontendDir.resolve("data.json"), "{}");

        handler = new FrontendHandler(new AppPaths(tempDir.resolve("data"), frontendDir));
        response = new MockHttpServletResponse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/index.html,   'public, max-age=600'",
            "/app.js,       'public, max-age=86400'",
            "/style.css,    'public, max-age=86400'",
            "/logo.png,     'public, max-age=31536000'",
            "/favicon.ico,  'public, max-age=31536000'",
            "/data.json,    'no-cache, no-store, must-revalidate'",
    })
    void cacheHeaders(String path, String expectedCacheControl) throws IOException {
        handler.handle(path, response);

        assertThat(response.getHeader("Cache-Control")).isEqualTo(expectedCacheControl);
    }

    @Test
    void noCacheResponsesCarryPragmaAndExpires() throws IOException {
        handler.handle("/data.json", response);

        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("Expires")).isEqualTo("0");
    }

    @Test
    void servesFiles() throws IOException {
        handler.handle("/app.js", response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("console.log('hello')");
    }

    @Test
    void servesIndexHtmlForTheRootPath() throws IOException {
        handler.handle("/", response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("<html></html>");
    }

    @Test
    void missingFileReturns404() throws IOException {
        handler.handle("/nope.js", response);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void pathTraversalOutsideTheFrontendDirectoryReturns404() throws IOException {
        Files.writeString(tempDir.resolve("secret.txt"), "top secret");

        handler.handle("/../secret.txt", response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain("top secret");
    }
}
