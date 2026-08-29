package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.QueueEncodeUseCase;
import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.EncodeJob;
import com.fjourdren.theatrum.domain.model.FileMatch;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** Detects unencoded videos and sends them to the encode queue. */
@Component
@Slf4j
@RequiredArgsConstructor
public class VideoUnencodedDetector {

    private final ApplicationService appService;
    private final QueueEncodeUseCase encodeQueue;
    private final StoragePort storage;
    private final PathTemplateService templateService;
    private final AppPaths appPaths;

    /** Scans for unencoded videos and queues them for encoding. */
    public void detectAndQueueVideos() {
        log.info("Starting video detection");
        try {
            for (Stream stream : appService.getChannels().values()) {
                // Faithful port of the Go guard, which uses && (not ||).
                if (stream.type() != StreamType.VIDEO_UNENCODED && stream.videoInputPath().isEmpty()) {
                    continue;
                }
                scan(stream);
            }
        } finally {
            log.info("Video detection completed");
        }
    }

    private void scan(Stream stream) {
        int nbVideosToEncode = 0;

        log.info("Searching videos for stream {}", stream.path());

        List<FileMatch> filesToEncode;
        try {
            String pattern = appPaths.videoDir().resolve(stream.videoInputPath()).toString();
            filesToEncode = storage.searchFiles(pattern, VideoConstants.VALID_VIDEO_EXTENSIONS);
        } catch (Exception e) {
            log.error("Error searching for videos in {}: {}", stream.path(), e.getMessage());
            return;
        }

        String outputTemplate = appPaths.videoDir()
                .resolve(stream.path())
                .resolve(TemplateConstants.FILENAME_VAR)
                .toString();

        for (FileMatch file : filesToEncode) {
            // Template the output path with path variables for the encoded video
            String outputPath;
            try {
                outputPath = templateService.replacePlaceholders(outputTemplate, file.vars());
            } catch (RuntimeException e) {
                log.error("Error replacing placeholders: {}", e.getMessage());
                continue;
            }

            nbVideosToEncode++;

            if (!encodeQueue.enqueue(new EncodeJob(file.path(), Path.of(outputPath), stream))) {
                log.error("Error queueing video {}: encode queue is shut down", file.path());
                continue;
            }

            log.info("Queued video for encoding: {}", file.path());
        }

        log.info("Found {} videos to encode for stream {}", nbVideosToEncode, stream.path());
    }
}
