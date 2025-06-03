package repositories

import (
	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strconv"
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

func addFilter(args []string, qualities map[string]models.Quality) []string {
	// Start building the filter complex string
	filterComplex := "[0:v]split=" + fmt.Sprintf("%d", len(qualities))
	
	// Add input labels for each split
	for i := 0; i < len(qualities); i++ {
		filterComplex += fmt.Sprintf("[v%d]", i)
	}
	filterComplex += ";"
	
	// Add scale filters for each quality
	index := 0
	for _, quality := range qualities {
		filterComplex += fmt.Sprintf("[v%d]scale=%d:%d[v%dout];", index, quality.Width, quality.Height, index)
		index++
	}

	// Remove the trailing semicolon
	filterComplex = filterComplex[:len(filterComplex)-1]
	
	// Add the filter complex to args
	args = append(args, "-filter_complex", filterComplex)
	
	return args
}

func addVideoCodec(args []string, qualities map[string]models.Quality) []string {
	index := 0
	for _, quality := range qualities {
		// Parse bitrate string to float (remove 'k' and convert)
		bitrateStr := strings.TrimSuffix(quality.Bitrate, "k")
		bitrate, _ := strconv.ParseFloat(bitrateStr, 64)
		
		// Add mapping for video stream
		args = append(args, "-map", fmt.Sprintf("[v%dout]", index))
		
		// Add video encoding parameters
		args = append(args,
			fmt.Sprintf("-c:v:%d", index), "libx264",
			fmt.Sprintf("-b:v:%d", index), quality.Bitrate,
			fmt.Sprintf("-maxrate:v:%d", index), fmt.Sprintf("%.0fk", bitrate*0.6666667),
			fmt.Sprintf("-bufsize:v:%d", index), quality.Bitrate,
		)
		index++
	}
	return args
}

func addAudioCodec(args []string, qualities map[string]models.Quality) []string {
	index := 0
	for _, quality := range qualities {
		args = append(args,
			"-map", "a:0",
			fmt.Sprintf("-c:a:%d", index), quality.Audio.Codec,
			fmt.Sprintf("-b:a:%d", index), quality.Audio.Bitrate,
		)
		index++
	}
	return args
}

func addMuxing(args []string, outputPath string, distribution models.Distribution, qualities map[string]models.Quality) []string {
	outputDir := filepath.Dir(outputPath)

	// Generate the stream_map
	streamMap := ""
	index := 0
	for qualityName := range qualities {
		if index > 0 {
			streamMap += " "
		}
		streamMap += fmt.Sprintf("v:%d,a:%d,name:%s", index, index, qualityName)
		index++
	}

	// Add HLS parameters
	args = append(args,
		"-f", "hls",
		"-hls_time", fmt.Sprintf("%d", distribution.Hls.SegmentDuration),
		"-var_stream_map", streamMap,
		"-hls_segment_filename", path.Join(outputDir, "%v", constants.SegmentName),
		"-master_pl_name", constants.MasterPlaylist,
		path.Join(outputDir, "%v", constants.SubPlaylist),
	)

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
	args = addFilter(args, qualities)
	args = addVideoCodec(args, qualities)
	args = addAudioCodec(args, qualities)
	args = addMuxing(args, outputPath, distribution, qualities)

	if e.DryRun {
		log.Printf("Prepared FFmpeg command: \n%s %s\n\n", e.ffmpegPath, strings.Join(args, " "))

		// Only print the command, do not execute
		return nil
	}

	// Prepare the command
	cmd := exec.Command(e.ffmpegPath, args...)

	// Redirect output to see FFmpeg logs
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	log.Printf("Executing FFmpeg command: %s %v", e.ffmpegPath, args)
	
	err := cmd.Run()
	if err != nil {
		log.Printf("FFmpeg execution failed: %v", err)
		return fmt.Errorf("ffmpeg execution failed: %v", err)
	}

	log.Printf("Successfully encoded video to %s", outputPath)
	return nil
}
