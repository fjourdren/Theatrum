package streamcmd

import "Theatrum/domain/models"

// OutputMode determines the muxer used by FFmpeg
type OutputMode int

const (
	OutputModeHLS  OutputMode = iota // HLS-only (ts segments, quality subdirs)
	OutputModeDASH                   // DASH-only (m4s segments, flat layout)
	OutputModeDual                   // Dual: DASH muxer with -hls_playlist 1 (both MPD and M3U8, m4s segments)
)

// DetermineOutputMode returns the appropriate output mode from a Distribution config.
func DetermineOutputMode(dist models.Distribution) OutputMode {
	if dist.HlsEnabled() && dist.DashEnabled() {
		return OutputModeDual
	}
	if dist.DashEnabled() {
		return OutputModeDASH
	}
	return OutputModeHLS
}
