package models

type Hls struct {
	SegmentDuration int
}

type Distribution struct {
	Hls Hls
}
