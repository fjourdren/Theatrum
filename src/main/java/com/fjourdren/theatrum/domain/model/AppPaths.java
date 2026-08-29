package com.fjourdren.theatrum.domain.model;

import java.nio.file.Path;

/**
 * Runtime directories. Mirrors the Go {@code constants.VideoDir} / {@code constants.FrontendDir}
 * package variables, but injected instead of global so tests can point at a temp dir.
 */
public record AppPaths(Path videoDir, Path frontendDir) {

    /** Defaults relative to the process working directory, like the Go build. */
    public static AppPaths defaults() {
        Path workDir = Path.of(System.getProperty("user.dir"));
        return new AppPaths(workDir.resolve("data"), workDir.resolve("frontend"));
    }
}
