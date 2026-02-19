package repositories

import (
	"Theatrum/shared/ffmpegargs"
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strings"
)

// FfmpegEncoder implements the EncoderPort interface using FFmpeg
type FfmpegEncoder struct {
	ffmpegPath string
	DryRun     bool // If true, only print the command, do not execute
}

// NewFfmpegEncoder creates a new instance of FfmpegEncoder
func NewFfmpegEncoder() repositories.EncoderPort {
	return &FfmpegEncoder{ffmpegPath: "ffmpeg"}
}

func addInput(args []string, inputPath string) []string {
	return append(args, "-i", inputPath)
}

func addMuxing(args []string, outputPath string, distribution models.Distribution, qualities map[string]models.Quality) []string {
	outputDir := filepath.Dir(outputPath)

	if distribution.DashEnabled() {
		// DASH or Dual mode
		segDur := distribution.Dash.SegmentDuration

		args = append(args,
			"-f", "dash",
			"-seg_duration", fmt.Sprintf("%d", segDur),
			"-use_template", "1",
			"-use_timeline", "1",
			"-init_seg_name", constants.DashInitSegName,
			"-media_seg_name", constants.DashSegName,
			"-adaptation_sets", "id=0,streams=v id=1,streams=a",
		)

		if distribution.IsDualMode() {
			args = append(args, "-hls_playlist", "1")
		}

		args = append(args, path.Join(outputDir, constants.DashManifest))
	} else {
		// HLS-only mode (unchanged)
		streamMap := ffmpegargs.BuildVarStreamMap(qualities)

		args = append(args,
			"-f", "hls",
			"-hls_time", fmt.Sprintf("%d", distribution.Hls.SegmentDuration),
			"-var_stream_map", streamMap,
			"-hls_segment_filename", path.Join(outputDir, "%v", constants.SegmentName),
			"-master_pl_name", constants.MasterPlaylist,
			path.Join(outputDir, "%v", constants.SubPlaylist),
		)
	}

	return args
}

func (e *FfmpegEncoder) EncodeVideo(inputPath string, outputPath string, qualities map[string]models.Quality, distribution models.Distribution) error {
	// Ensure output directory exists
	outputDir := path.Dir(outputPath)
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return fmt.Errorf("failed to create output directory: %v", err)
	}

	args := []string{}
	args = addInput(args, inputPath)
	args = ffmpegargs.AddFilter(args, qualities)
	args = ffmpegargs.AddVideoCodec(args, qualities)
	args = ffmpegargs.AddAudioCodec(args, qualities)
	args = addMuxing(args, outputPath, distribution, qualities)

	if e.DryRun {
		log.Printf("Prepared FFmpeg command: %s %s", e.ffmpegPath, strings.Join(args, " "))

		// Only print the command, do not execute
		return nil
	}

	// Prepare the command
	cmd := exec.Command(e.ffmpegPath, args...)

	// Redirect output to see FFmpeg logs
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	log.Printf("Executing FFmpeg command: %s %s", e.ffmpegPath, strings.Join(args, " "))

	err := cmd.Run()
	if err != nil {
		log.Printf("FFmpeg execution failed: %v", err)
		return fmt.Errorf("ffmpeg execution failed: %v", err)
	}

	log.Printf("Successfully encoded video to %s", outputPath)
	return nil
}
