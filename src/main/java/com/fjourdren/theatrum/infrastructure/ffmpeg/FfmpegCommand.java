package com.fjourdren.theatrum.infrastructure.ffmpeg;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Stream;
import lombok.experimental.UtilityClass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the FFmpeg argument vector for a live or restreamed channel. The returned list starts with
 * {@code "ffmpeg"}; launching the process is the caller's job.
 */
@UtilityClass
public final class FfmpegCommand {

    /**
     * Builds the FFmpeg argv for {@code stream}.
     *
     * @param sourceUrl empty or null to read from stdin (FLV pipe for RTMP), otherwise the URL to
     *                  pull from (restream)
     * @param outputDir directory the segments and playlists/manifest are written to
     * @param mode      muxer to use; see {@link OutputMode#determine(Distribution)}
     */
    public static List<String> create(String sourceUrl, Path outputDir, Stream stream, OutputMode mode) {
        return switch (mode) {
            case DASH, DUAL -> dashCommand(sourceUrl, outputDir, stream, mode);
            case HLS -> hlsCommand(sourceUrl, outputDir, stream);
        };
    }

    /** {@code -hls_flags}: recording keeps every segment, otherwise old ones are deleted. */
    static String hlsFlags(boolean recording) {
        return recording
                ? "temp_file+independent_segments"
                : "delete_segments+temp_file+independent_segments";
    }

    /** {@code -extra_window_size}: recording keeps every segment, otherwise old ones are deleted. */
    static String dashExtraWindowSize(boolean recording) {
        return recording ? "999999" : "0";
    }

    static List<String> inputArgs(String sourceUrl) {
        return sourceUrl == null || sourceUrl.isEmpty()
                ? List.of("-f", "flv", "-i", "pipe:0")
                : List.of("-i", sourceUrl);
    }

    private static List<String> baseArgs(String sourceUrl) {
        var args = new ArrayList<>(List.of(
                FfmpegConstants.BINARY,
                "-re",
                "-fflags", "+nobuffer",
                "-flags", "low_delay"));
        args.addAll(inputArgs(sourceUrl));
        return args;
    }

    /** Transcoding args, or nothing when the stream is passthrough (codec copy). */
    private static List<String> withEncoding(List<String> args, Stream stream) {
        if (!stream.multiQuality()) {
            return args;
        }
        var qualities = stream.qualities();
        var out = FfmpegArgs.addFilter(args, qualities);
        out = FfmpegArgs.addVideoCodecLive(out, qualities);
        return FfmpegArgs.addAudioCodec(out, qualities);
    }

    /** HLS-only output: passthrough into a single playlist, or multi-quality into %v subdirs. */
    private static List<String> hlsCommand(String sourceUrl, Path outputDir, Stream stream) {
        var hls = stream.distribution().hls();
        boolean multiQuality = stream.multiQuality();

        var args = new ArrayList<>(withEncoding(baseArgs(sourceUrl), stream));
        if (!multiQuality) {
            Collections.addAll(args, "-c:v", "copy", "-c:a", "copy");
        }
        Collections.addAll(args,
                "-f", "hls",
                "-hls_time", String.valueOf(hls.segmentDuration()),
                "-hls_list_size", String.valueOf(hls.windowSize()),
                "-hls_flags", hlsFlags(stream.record().enabled()),
                "-hls_segment_type", "mpegts",
                "-hls_allow_cache", "0");
        if (multiQuality) {
            Collections.addAll(args,
                    "-var_stream_map", FfmpegArgs.buildVarStreamMap(stream.qualities()),
                    "-master_pl_name", VideoConstants.MASTER_PLAYLIST);
        }

        Path segmentDir = multiQuality
                ? outputDir.resolve(FfmpegConstants.QUALITY_DIR_PLACEHOLDER)
                : outputDir;
        Collections.addAll(args,
                "-hls_segment_filename", segmentDir.resolve(VideoConstants.SEGMENT_NAME).toString());
        args.add(segmentDir.resolve(VideoConstants.SUB_PLAYLIST).toString());
        return args;
    }

    /** DASH or dual output; both use the DASH muxer, dual adds {@code -hls_playlist 1}. */
    private static List<String> dashCommand(String sourceUrl, Path outputDir, Stream stream, OutputMode mode) {
        var dist = stream.distribution();
        boolean multiQuality = stream.multiQuality();

        var args = new ArrayList<>(withEncoding(baseArgs(sourceUrl), stream));
        if (!multiQuality) {
            Collections.addAll(args, "-c:v", "copy", "-c:a", "copy");
        }
        Collections.addAll(args,
                "-f", "dash",
                "-seg_duration", String.valueOf(dashSegmentDuration(dist)),
                "-window_size", String.valueOf(dashWindowSize(dist)),
                "-extra_window_size", dashExtraWindowSize(stream.record().enabled()),
                "-streaming", "1",
                "-ldash", "1",
                "-use_template", "1",
                "-use_timeline", "0",
                "-remove_at_exit", "0",
                "-init_seg_name", VideoConstants.DASH_INIT_SEG_NAME,
                "-media_seg_name", VideoConstants.DASH_SEG_NAME);
        if (multiQuality) {
            Collections.addAll(args, "-adaptation_sets", "id=0,streams=v id=1,streams=a");
        }
        if (mode == OutputMode.DUAL) {
            Collections.addAll(args, "-hls_playlist", "1");
        }
        args.add(outputDir.resolve(VideoConstants.DASH_MANIFEST).toString());
        return args;
    }

    private static int dashSegmentDuration(Distribution dist) {
        return dist.dashEnabled() ? dist.dash().segmentDuration() : dist.hls().segmentDuration();
    }

    private static int dashWindowSize(Distribution dist) {
        return dist.dashEnabled() ? dist.dash().windowSize() : dist.hls().windowSize();
    }
}
