package com.fjourdren.theatrum.application.port.out;

import com.fjourdren.theatrum.domain.model.FileMatch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface StoragePort {

    byte[] readFile(Path path) throws IOException;

    void writeFile(Path path, byte[] data) throws IOException;

    void deleteFile(Path path) throws IOException;

    /** Lists files matching a glob pattern. */
    List<Path> listFiles(String pattern) throws IOException;

    long getFileSize(Path path) throws IOException;

    /**
     * Searches for files beneath directories matching {@code pattern}, optionally filtered by extension.
     *
     * <p>Pattern rules:
     * <ul>
     *   <li>Placeholders are written {@code {like_this}} and must span an entire path segment</li>
     *   <li>The pattern must not contain empty segments or path traversal attempts</li>
     * </ul>
     */
    List<FileMatch> searchFiles(String pattern, List<String> extensions) throws IOException;
}
