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
	HTTPPort int  `yaml:"http"`
	RTMPPort int  `yaml:"rtmp"`
	RTMP     RTMP `yaml:"rtmp_config,omitempty"`
}

type RTMP struct {
	ReconnectDelay int `yaml:"reconnect_delay,omitempty"` // Seconds to wait before cleaning up disconnected stream (default: 30)
	CleanupDelay   int `yaml:"cleanup_delay,omitempty"`   // Seconds to wait before removing stream files (default: 30)
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
	Hls  *Hls  `yaml:"hls,omitempty"`
	Dash *Dash `yaml:"dash,omitempty"`
}

type Hls struct {
	SegmentDuration int `yaml:"segment_duration"`
	WindowSize      int `yaml:"window_size,omitempty"`
}

type Dash struct {
	SegmentDuration int `yaml:"segment_duration"`
	WindowSize      int `yaml:"window_size,omitempty"`
}

type Stream struct {
	Type         string             `yaml:"type"`
	Path         string             `yaml:"path"`
	Qualities    map[string]Quality `yaml:"qualities,omitempty"`
	Distribution Distribution       `yaml:"distribution"`

	// Specific fields for video unencoded streams
	VideoInputPath      string `yaml:"video_input_path"`
	DeleteAfterEncoding bool   `yaml:"delete_after_encoding,omitempty"` // If enabled, delete the source file after video encoding (default: false)

	// Specific fields for live streams
	LiveStreamKey     string `yaml:"live_stream_key"`
	AuthTokenTemplate string `yaml:"auth_token_template"` // Template for XOR auth, e.g. "{username}" or "{room_id}{username}"
	Record            Record    `yaml:"record,omitempty"`
	Viewers           Viewers   `yaml:"viewers,omitempty"`
	Views             Views     `yaml:"views,omitempty"`
	Thumbnail         Thumbnail `yaml:"thumbnail,omitempty"`

	// Specific fields for restream streams
	SourceURL string `yaml:"source_url,omitempty"`
}

type Record struct {
	Enabled bool   `yaml:"enabled,omitempty"`
	Path    string `yaml:"path,omitempty"`
}

type Viewers struct {
	Enabled bool `yaml:"enabled,omitempty"`
	Window  int  `yaml:"window,omitempty"`
}

type Views struct {
	Enabled bool `yaml:"enabled,omitempty"`
	Window  int  `yaml:"window,omitempty"`
}

type Thumbnail struct {
	Enabled  bool `yaml:"enabled,omitempty"`
	Interval int  `yaml:"interval,omitempty"`
}

type StreamTemplate struct {
	Stream Stream `yaml:"stream"`
}

type Channel struct {
	Stream Stream `yaml:"stream,omitempty"`
}