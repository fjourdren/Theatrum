package models

type Hls struct {
	SegmentDuration int
	PlaylistLength  int
}

type Distribution struct {
	Hls Hls
}
