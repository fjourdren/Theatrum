package streamcmd

import (
	"context"
	"fmt"
	"os/exec"
	"path/filepath"

	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/shared/ffmpegargs"
)

// inputArgs returns the FFmpeg input arguments based on the source URL.
// When sourceURL is empty, it reads from stdin (FLV pipe). When set, it reads from the URL directly.
func inputArgs(sourceURL string) []string {
	if sourceURL != "" {
		return []string{"-i", sourceURL}
	}
	return []string{"-f", "flv", "-i", "pipe:0"}
}

// CreateFFmpegCommand creates an FFmpeg command with the specified settings.
// When sourceURL is empty, FFmpeg reads from stdin (FLV pipe for RTMP).
// When sourceURL is set, FFmpeg reads directly from that URL (for restreaming).
// When the stream has qualities defined, it produces multi-quality output.
// Otherwise it uses codec copy for passthrough.
func CreateFFmpegCommand(ctx context.Context, sourceURL string, outputDir string, stream *models.Stream, mode OutputMode) *exec.Cmd {
	multiQuality := len(stream.Qualities) > 0
	switch mode {
	case OutputModeDASH, OutputModeDual:
		if multiQuality {
			return createDashMultiQualityCommand(ctx, sourceURL, outputDir, stream, mode)
		}
		return createDashCopyCommand(ctx, sourceURL, outputDir, stream, mode)
	default: // OutputModeHLS
		if multiQuality {
			return createMultiQualityCommand(ctx, sourceURL, outputDir, stream)
		}
		return createCopyCommand(ctx, sourceURL, outputDir, stream)
	}
}

// hlsFlags returns the appropriate -hls_flags value based on recording mode.
func hlsFlags(recording bool) string {
	if recording {
		return "temp_file+independent_segments"
	}
	return "delete_segments+temp_file+independent_segments"
}

// dashExtraWindowSize returns the extra_window_size for DASH muxer.
// Recording keeps all segments; non-recording deletes old ones.
func dashExtraWindowSize(recording bool) string {
	if recording {
		return "999999"
	}
	return "0"
}

// createCopyCommand creates an FFmpeg command that copies codecs without transcoding (passthrough, HLS-only).
func createCopyCommand(ctx context.Context, sourceURL string, outputDir string, stream *models.Stream) *exec.Cmd {
	segmentDuration := fmt.Sprintf("%d", stream.Distribution.Hls.SegmentDuration)
	windowSize := fmt.Sprintf("%d", stream.Distribution.Hls.WindowSize)

	args := []string{
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
	}
	args = append(args, inputArgs(sourceURL)...)
	args = append(args,
		"-c:v", "copy",
		"-c:a", "copy",
		"-f", "hls",
		"-hls_time", segmentDuration,
		"-hls_list_size", windowSize,
		"-hls_flags", hlsFlags(stream.Record.Enabled),
		"-hls_segment_type", "mpegts",
		"-hls_allow_cache", "0",
		"-hls_segment_filename", filepath.Join(outputDir, constants.SegmentName),
		filepath.Join(outputDir, constants.SubPlaylist),
	)

	return exec.CommandContext(ctx, "ffmpeg", args...)
}

// createMultiQualityCommand creates an FFmpeg command that transcodes into multiple quality levels (HLS-only).
func createMultiQualityCommand(ctx context.Context, sourceURL string, outputDir string, stream *models.Stream) *exec.Cmd {
	segmentDuration := fmt.Sprintf("%d", stream.Distribution.Hls.SegmentDuration)
	windowSize := fmt.Sprintf("%d", stream.Distribution.Hls.WindowSize)

	args := []string{
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
	}
	args = append(args, inputArgs(sourceURL)...)

	args = ffmpegargs.AddFilter(args, stream.Qualities)
	args = ffmpegargs.AddVideoCodecLive(args, stream.Qualities)
	args = ffmpegargs.AddAudioCodec(args, stream.Qualities)

	streamMap := ffmpegargs.BuildVarStreamMap(stream.Qualities)

	args = append(args,
		"-f", "hls",
		"-hls_time", segmentDuration,
		"-hls_list_size", windowSize,
		"-hls_flags", hlsFlags(stream.Record.Enabled),
		"-hls_segment_type", "mpegts",
		"-hls_allow_cache", "0",
		"-var_stream_map", streamMap,
		"-master_pl_name", constants.MasterPlaylist,
		"-hls_segment_filename", filepath.Join(outputDir, "%v", constants.SegmentName),
		filepath.Join(outputDir, "%v", constants.SubPlaylist),
	)

	return exec.CommandContext(ctx, "ffmpeg", args...)
}

// createDashCopyCommand creates an FFmpeg command for DASH/dual passthrough (codec copy).
func createDashCopyCommand(ctx context.Context, sourceURL string, outputDir string, stream *models.Stream, mode OutputMode) *exec.Cmd {
	dist := stream.Distribution
	var segDur, winSize int
	if dist.DashEnabled() {
		segDur = dist.Dash.SegmentDuration
		winSize = dist.Dash.WindowSize
	} else {
		segDur = dist.Hls.SegmentDuration
		winSize = dist.Hls.WindowSize
	}

	args := []string{
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
	}
	args = append(args, inputArgs(sourceURL)...)
	args = append(args,
		"-c:v", "copy",
		"-c:a", "copy",
		"-f", "dash",
		"-seg_duration", fmt.Sprintf("%d", segDur),
		"-window_size", fmt.Sprintf("%d", winSize),
		"-extra_window_size", dashExtraWindowSize(stream.Record.Enabled),
		"-streaming", "1",
		"-ldash", "1",
		"-use_template", "1",
		"-use_timeline", "0",
		"-remove_at_exit", "0",
		"-init_seg_name", constants.DashInitSegName,
		"-media_seg_name", constants.DashSegName,
	)

	if mode == OutputModeDual {
		args = append(args, "-hls_playlist", "1")
	}

	args = append(args, filepath.Join(outputDir, constants.DashManifest))
	return exec.CommandContext(ctx, "ffmpeg", args...)
}

// createDashMultiQualityCommand creates an FFmpeg command for DASH/dual multi-quality transcoding.
func createDashMultiQualityCommand(ctx context.Context, sourceURL string, outputDir string, stream *models.Stream, mode OutputMode) *exec.Cmd {
	dist := stream.Distribution
	var segDur, winSize int
	if dist.DashEnabled() {
		segDur = dist.Dash.SegmentDuration
		winSize = dist.Dash.WindowSize
	} else {
		segDur = dist.Hls.SegmentDuration
		winSize = dist.Hls.WindowSize
	}

	args := []string{
		"-re",
		"-fflags", "+nobuffer",
		"-flags", "low_delay",
	}
	args = append(args, inputArgs(sourceURL)...)

	args = ffmpegargs.AddFilter(args, stream.Qualities)
	args = ffmpegargs.AddVideoCodecLive(args, stream.Qualities)
	args = ffmpegargs.AddAudioCodec(args, stream.Qualities)

	args = append(args,
		"-f", "dash",
		"-seg_duration", fmt.Sprintf("%d", segDur),
		"-window_size", fmt.Sprintf("%d", winSize),
		"-extra_window_size", dashExtraWindowSize(stream.Record.Enabled),
		"-streaming", "1",
		"-ldash", "1",
		"-use_template", "1",
		"-use_timeline", "0",
		"-remove_at_exit", "0",
		"-init_seg_name", constants.DashInitSegName,
		"-media_seg_name", constants.DashSegName,
		"-adaptation_sets", "id=0,streams=v id=1,streams=a",
	)

	if mode == OutputModeDual {
		args = append(args, "-hls_playlist", "1")
	}

	args = append(args, filepath.Join(outputDir, constants.DashManifest))
	return exec.CommandContext(ctx, "ffmpeg", args...)
}
