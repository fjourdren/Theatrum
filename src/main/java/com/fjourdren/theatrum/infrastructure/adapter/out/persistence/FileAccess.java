package com.fjourdren.theatrum.infrastructure.adapter.out.persistence;

import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.constant.PathConstants;
import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.model.FileMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** File system implementation of {@link StoragePort}. */
@Component
@Slf4j
public class FileAccess implements StoragePort {


    @Override
    public byte[] readFile(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    /** Plain write: like Go's {@code os.WriteFile}, it does not create parent directories. */
    @Override
    public void writeFile(Path path, byte[] data) throws IOException {
        Files.write(path, data);
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        Files.delete(path);
    }

    @Override
    public List<Path> listFiles(String pattern) throws IOException {
        int slash = pattern.lastIndexOf('/');
        Path dir = slash < 0 ? Path.of(".") : Path.of(pattern.substring(0, slash + 1));
        String glob = slash < 0 ? pattern : pattern.substring(slash + 1);

        // Go's filepath.Glob ignores file system errors and only fails on a bad pattern.
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        var files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            stream.forEach(files::add);
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    @Override
    public long getFileSize(Path path) throws IOException {
        return Files.size(path);
    }

    /**
     * Walks the file system and returns every file sitting beneath a path matching {@code pattern},
     * together with the values captured by the pattern's {@code {placeholders}} plus a
     * {@code FILENAME} entry.
     *
     * <p>Placeholders span a whole path segment, everything else is literal. A pattern that does not
     * end with '/' ends with a <em>filename</em>: the match is then anchored and the basename must be
     * equal to the pattern's basename.
     */
    @Override
    public List<FileMatch> searchFiles(String pattern, List<String> extensions) throws IOException {
        validatePattern(pattern);

        // Deepest directory the pattern can reach; anything below is not worth walking.
        int maxDepth = countSlashes(pattern);

        var varNames = new ArrayList<String>();
        var regex = new StringBuilder("^");
        // Longest literal prefix: where the walk starts (big speed-up on large trees).
        var walkRoot = new StringBuilder();
        boolean varAlreadyFound = false;
        boolean hasFilename = false;

        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == TemplateConstants.VAR_OPEN) {
                varAlreadyFound = true;
                regex.append("([^/]+)");

                int end = pattern.indexOf(TemplateConstants.VAR_CLOSE, i + 1);
                if (end == -1) {
                    throw new IllegalArgumentException("unclosed placeholder in pattern \"" + pattern + "\"");
                }
                String name = pattern.substring(i + 1, end);
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("empty placeholder in pattern \"" + pattern + "\"");
                }
                varNames.add(name);
                i = end; // skip the placeholder, including its closing brace
            } else if (ch != TemplateConstants.VAR_CLOSE) {
                regex.append(Pattern.quote(String.valueOf(ch)));

                if (!varAlreadyFound) {
                    walkRoot.append(ch); // only literals belong in the root
                }
                if (i == pattern.length() - 1 && ch != '/') {
                    hasFilename = true;
                }
            }
        }
        regex.append(hasFilename ? "$" : "/.+$"); // without a filename we expect files *beneath* the dir
        Pattern re = Pattern.compile(regex.toString());

        boolean matchFilename = hasFilename;
        String patternFilename = basename(pattern);
        Path root = Path.of(walkRoot.isEmpty() ? "." : walkRoot.toString()).normalize();

        var matches = new ArrayList<FileMatch>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return countSlashes(normalize(dir)) > maxDepth
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String path = normalize(file);

                    Matcher matcher = re.matcher(path);
                    if (!matcher.matches() || matcher.groupCount() != varNames.size()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (matchFilename && !basename(path).equals(patternFilename)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!extensions.isEmpty() && !hasExtension(path, extensions)) {
                        return FileVisitResult.CONTINUE;
                    }

                    var vars = new LinkedHashMap<String, String>();
                    for (int i = 0; i < varNames.size(); i++) {
                        vars.put(varNames.get(i), matcher.group(i + 1));
                    }
                    if (path.contains("/")) {
                        String filename = basename(path);
                        if (!filename.isEmpty() && !filename.equals(".") && !filename.equals("/")) {
                            vars.put("FILENAME", filename);
                        }
                    }

                    matches.add(new FileMatch(Path.of(path), vars));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Error searching files: {}", e.getMessage());
            throw e;
        }

        // walkFileTree order is unspecified; sort to match Go's lexical WalkDir order.
        matches.sort(Comparator.comparing(FileMatch::path));
        return matches;
    }

    /** Security checks preventing path traversal and other surprises. */
    private static void validatePattern(String pattern) {
        if (pattern.contains("..")) {
            throw new IllegalArgumentException("pattern cannot contain '..' (path traversal attempt)");
        }
        if (pattern.contains("\\")) {
            throw new IllegalArgumentException("pattern cannot contain backslashes (use forward slashes)");
        }

        // Empty segments; an empty first segment (leading slash) is allowed.
        String[] segments = pattern.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() && !segment.equals(segments[0])) {
                throw new IllegalArgumentException("pattern cannot contain empty segments");
            }
        }

        for (String dangerous : PathConstants.DANGEROUS_IN_GLOB) {
            if (pattern.contains(dangerous)) {
                throw new IllegalArgumentException("pattern contains potentially dangerous character: " + dangerous);
            }
        }

        for (int i = 1; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == pattern.charAt(i - 1)
                    && (ch == TemplateConstants.VAR_OPEN || ch == TemplateConstants.VAR_CLOSE)) {
                throw new IllegalArgumentException("nested placeholders are not allowed");
            }
        }
    }

    private static boolean hasExtension(String path, List<String> extensions) {
        String pathExt = extension(path).toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(ext -> ext.toLowerCase(Locale.ROOT).equals(pathExt));
    }

    /** Extension including the dot, empty when there is none (Go's filepath.Ext). */
    private static String extension(String path) {
        String name = basename(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static int countSlashes(String value) {
        return (int) value.chars().filter(c -> c == '/').count();
    }
}
