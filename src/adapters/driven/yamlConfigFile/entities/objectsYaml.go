package entities

type Config struct {
	Application     Application               `yaml:"application"`
	Server          Server                    `yaml:"server"`
	StreamTemplates map[string]StreamTemplate `yaml:"stream_templates"`
	Channels        map[string]Channel        `yaml:"channels"`
}

type Application struct {
	PublicPath         string             `yaml:"public_path"`
	AllStreamsPlaylist AllStreamsPlaylist `yaml:"all_streams_playlist"`
}

type AllStreamsPlaylist struct {
	Enabled bool   `yaml:"enabled"`
	Path    string `yaml:"path"`
}

type Server struct {
	HTTPPort int `yaml:"http"`
	RTMPPort int `yaml:"rtmp"`
}

type Audio struct {
	Bitrate string `yaml:"bitrate"`
	Codec   string `yaml:"codec"`
}

type Quality struct {
	Width     int    `yaml:"width"`
	Height    int    `yaml:"height"`
	Framerate int    `yaml:"framerate"`
	Bitrate   string `yaml:"bitrate"`
	Codec     string `yaml:"codec"`
	Audio     Audio  `yaml:"audio"`
}

type Distribution struct {
	Hls Hls `yaml:"hls"`
}

type Hls struct {
	SegmentDuration int `yaml:"segment_duration"`
}

type Stream struct {
	Type         string             `yaml:"type"`
	Path         string             `yaml:"path"`
	Qualities    map[string]Quality `yaml:"qualities"`
	Distribution Distribution       `yaml:"distribution"`

	// Specific fields for video unencoded streams
	VideoInputPath      string `yaml:"video_input_path"`
	DeleteAfterEncoding bool   `yaml:"delete_after_encoding,omitempty"` // If enabled, delete the source file after video encoding (default: false)

	// Specific fields for live streams
	LiveStreamKey string `yaml:"live_stream_key,omitempty"`
}

type StreamTemplate struct {
	Stream Stream `yaml:"stream"`
}

type Channel struct {
	Stream Stream `yaml:"stream,omitempty"`
}