package models

type Hls struct {
	SegmentDuration int
	WindowSize      int // Number of segments in the live playlist (default: 3)
}

type Distribution struct {
	Hls Hls
}
