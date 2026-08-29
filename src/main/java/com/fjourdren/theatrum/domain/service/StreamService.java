package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.ServeStreamUseCase;
import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Resolves where a stream's files live on disk. */
@Component
@RequiredArgsConstructor
public class StreamService implements ServeStreamUseCase {

    /** Resources that live at the stream root, never inside a quality subdirectory. */
    private static final Set<String> ROOT_RESOURCES = Set.of(
            VideoConstants.MASTER_PLAYLIST, VideoConstants.DASH_MANIFEST, VideoConstants.THUMBNAIL_FILE);

    private final PathTemplateService pathTemplateService;
    private final AppPaths appPaths;

    /**
     * Resolves the on-disk directory for a stream resource.
     *
     * @throws IllegalArgumentException if a template value cannot be sanitized
     */
    @Override
    public Path getStreamStoragePath(Stream stream, Map<String, String> templatingVars) {
        // Copied, not mutated in place: Go mutated the caller's map to inject the default quality.
        var vars = new LinkedHashMap<>(templatingVars);
        vars.putIfAbsent(TemplateConstants.VAR_NAME_QUALITY, VideoConstants.DEFAULT_QUALITY);

        var template = stream.path();

        // Only HLS-only mode uses quality subdirs.
        // DASH-enabled modes (DASH-only or dual) use a flat layout - no quality subdir appended.
        var hlsOnly = stream.distribution().hlsEnabled() && !stream.distribution().dashEnabled();

        var isRootResource = ROOT_RESOURCES.contains(vars.getOrDefault(TemplateConstants.VAR_NAME_RESOURCE, ""));

        if (hlsOnly && !isRootResource && !template.contains(TemplateConstants.QUALITY_VAR)) {
            template += "/" + vars.get(TemplateConstants.VAR_NAME_QUALITY);
        }

        return appPaths.videoDir().resolve(pathTemplateService.replacePlaceholders(template, vars));
    }
}
