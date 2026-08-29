package com.fjourdren.theatrum.infrastructure.adapter.out.encoder;

import com.fjourdren.theatrum.application.port.out.EncoderPort;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.infrastructure.ffmpeg.FfmpegArgs;
import com.fjourdren.theatrum.infrastructure.ffmpeg.FfmpegConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** VOD encoder: implements {@link EncoderPort} by shelling out to FFmpeg. */
@Component
@Slf4j
public class FfmpegEncoder implements EncoderPort {

    private final String ffmpegPath;

    /** When true, the command is only logged, never executed. */
    private final boolean dryRun;

    public FfmpegEncoder() {
        this(FfmpegConstants.BINARY, false);
    }

    public FfmpegEncoder(String ffmpegPath, boolean dryRun) {
        this.ffmpegPath = ffmpegPath;
        this.dryRun = dryRun;
    }

    @Override
    public void encodeVideo(Path inputPath, Path outputPath, Map<String, Quality> qualities,
                            Distribution distribution) throws IOException {
        Files.createDirectories(outputDir(outputPath));

        var args = buildArgs(inputPath, outputPath, qualities, distribution);
        String printable = ffmpegPath + " " + String.join(" ", args);

        if (dryRun) {
            log.info("Prepared FFmpeg command: {}", printable);
            return;
        }

        log.info("Executing FFmpeg command: {}", printable);

        var command = new ArrayList<String>(args.size() + 1);
        command.add(ffmpegPath);
        command.addAll(args);

        try {
            // Inherit stdout/stderr so FFmpeg logs stay visible, as the Go version does.
            int exitCode = new ProcessBuilder(command).inheritIO().start().waitFor();
            if (exitCode != 0) {
                log.error("FFmpeg execution failed: exit status {}", exitCode);
                throw new IOException("ffmpeg execution failed: exit status " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("FFmpeg execution failed: {}", e.toString());
            throw new IOException("ffmpeg execution failed: interrupted", e);
        }

        log.info("Successfully encoded video to {}", outputPath);
    }

    /** Builds the FFmpeg argument vector, without the executable itself. */
    static List<String> buildArgs(Path inputPath, Path outputPath, Map<String, Quality> qualities,
                                  Distribution distribution) {
        List<String> args = List.of("-i", inputPath.toString());
        args = FfmpegArgs.addFilter(args, qualities);
        args = FfmpegArgs.addVideoCodec(args, qualities);
        args = FfmpegArgs.addAudioCodec(args, qualities);
        return addMuxing(args, outputPath, distribution, qualities);
    }

    /** DASH (optionally with HLS playlists in dual mode), otherwise multi-quality HLS. */
    private static List<String> addMuxing(List<String> args, Path outputPath, Distribution distribution,
                                          Map<String, Quality> qualities) {
        Path outputDir = outputDir(outputPath);
        var out = new ArrayList<>(args);

        if (distribution.dashEnabled()) {
            Collections.addAll(out,
                    "-f", "dash",
                    "-seg_duration", String.valueOf(distribution.dash().segmentDuration()),
                    "-use_template", "1",
                    "-use_timeline", "1",
                    "-init_seg_name", VideoConstants.DASH_INIT_SEG_NAME,
                    "-media_seg_name", VideoConstants.DASH_SEG_NAME,
                    "-adaptation_sets", "id=0,streams=v id=1,streams=a");
            if (distribution.isDualMode()) {
                Collections.addAll(out, "-hls_playlist", "1");
            }
            out.add(outputDir.resolve(VideoConstants.DASH_MANIFEST).toString());
        } else {
            // %v is an FFmpeg placeholder for the quality name, not a real path segment.
            Path qualityDir = outputDir.resolve(FfmpegConstants.QUALITY_DIR_PLACEHOLDER);
            Collections.addAll(out,
                    "-f", "hls",
                    "-hls_time", String.valueOf(distribution.hls().segmentDuration()),
                    "-var_stream_map", FfmpegArgs.buildVarStreamMap(qualities),
                    "-hls_segment_filename", qualityDir.resolve(VideoConstants.SEGMENT_NAME).toString(),
                    "-master_pl_name", VideoConstants.MASTER_PLAYLIST,
                    qualityDir.resolve(VideoConstants.SUB_PLAYLIST).toString());
        }
        return out;
    }

    /** Go's {@code filepath.Dir}: a bare filename yields the current directory, never null. */
    private static Path outputDir(Path outputPath) {
        Path parent = outputPath.getParent();
        return parent != null ? parent : Path.of(".");
    }
}
