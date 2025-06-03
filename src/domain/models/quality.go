package models

type Audio struct {
	// Bitrate of the audio in bits per second
	Bitrate string
	// Codec of the audio
	Codec string
}

// Quality represents the video quality settings for a stream
type Quality struct {
	// Width of the video in pixels
	Width int
	// Height of the video in pixels
	Height int
	// FrameRate of the video in frames per second
	Framerate int
	// Bitrate of the video in bits per second
	Bitrate string
	// Codec of the video
	Codec string
	// Audio of the video
	Audio Audio
}