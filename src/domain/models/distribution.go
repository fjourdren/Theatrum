package models

type Hls struct {
	SegmentDuration int
	WindowSize      int // Number of segments in the live playlist (default: 3)
}

type Dash struct {
	SegmentDuration int
	WindowSize      int // Number of segments in the live manifest (default: 3)
}

type Distribution struct {
	Hls  *Hls
	Dash *Dash
}

func (d Distribution) HlsEnabled() bool {
	return d.Hls != nil
}

func (d Distribution) DashEnabled() bool {
	return d.Dash != nil
}

func (d Distribution) IsDualMode() bool {
	return d.HlsEnabled() && d.DashEnabled()
}

// SegmentDuration returns the segment duration from whichever format is configured.
// In dual mode, both must match (enforced by validation), so either is fine.
func (d Distribution) SegmentDuration() int {
	if d.Hls != nil {
		return d.Hls.SegmentDuration
	}
	if d.Dash != nil {
		return d.Dash.SegmentDuration
	}
	return 0
}

// WindowSize returns the window size from whichever format is configured.
func (d Distribution) WindowSize() int {
	if d.Hls != nil {
		return d.Hls.WindowSize
	}
	if d.Dash != nil {
		return d.Dash.WindowSize
	}
	return 3
}
