package ffmpegargs

import (
	"Theatrum/domain/models"
	"fmt"
	"strconv"
	"strings"
)

// AddFilter builds -filter_complex for split+scale across qualities.
func AddFilter(args []string, qualities map[string]models.Quality) []string {
	filterComplex := "[0:v]split=" + fmt.Sprintf("%d", len(qualities))

	for i := 0; i < len(qualities); i++ {
		filterComplex += fmt.Sprintf("[v%d]", i)
	}
	filterComplex += ";"

	index := 0
	for _, quality := range qualities {
		filterComplex += fmt.Sprintf("[v%d]scale=%d:%d[v%dout];", index, quality.Width, quality.Height, index)
		index++
	}

	// Remove the trailing semicolon
	filterComplex = filterComplex[:len(filterComplex)-1]

	args = append(args, "-filter_complex", filterComplex)
	return args
}

// AddVideoCodec adds per-quality video codec parameters.
func AddVideoCodec(args []string, qualities map[string]models.Quality) []string {
	index := 0
	for _, quality := range qualities {
		bitrateStr := strings.TrimSuffix(quality.Bitrate, "k")
		bitrate, _ := strconv.ParseFloat(bitrateStr, 64)

		args = append(args, "-map", fmt.Sprintf("[v%dout]", index))

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

// AddVideoCodecLive adds per-quality video codec parameters with live-streaming presets.
func AddVideoCodecLive(args []string, qualities map[string]models.Quality) []string {
	index := 0
	for _, quality := range qualities {
		bitrateStr := strings.TrimSuffix(quality.Bitrate, "k")
		bitrate, _ := strconv.ParseFloat(bitrateStr, 64)

		args = append(args, "-map", fmt.Sprintf("[v%dout]", index))

		args = append(args,
			fmt.Sprintf("-c:v:%d", index), "libx264",
			fmt.Sprintf("-b:v:%d", index), quality.Bitrate,
			fmt.Sprintf("-maxrate:v:%d", index), fmt.Sprintf("%.0fk", bitrate*0.6666667),
			fmt.Sprintf("-bufsize:v:%d", index), quality.Bitrate,
			fmt.Sprintf("-preset:v:%d", index), "veryfast",
			fmt.Sprintf("-tune:v:%d", index), "zerolatency",
		)
		index++
	}
	return args
}

// AddAudioCodec adds per-quality audio codec parameters.
func AddAudioCodec(args []string, qualities map[string]models.Quality) []string {
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

// BuildVarStreamMap builds the -var_stream_map string for multi-quality output.
func BuildVarStreamMap(qualities map[string]models.Quality) string {
	streamMap := ""
	index := 0
	for qualityName := range qualities {
		if index > 0 {
			streamMap += " "
		}
		streamMap += fmt.Sprintf("v:%d,a:%d,name:%s", index, index, qualityName)
		index++
	}
	return streamMap
}
