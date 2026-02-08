package models

type Server struct {
	HTTPPort int
	RTMPPort int
	RTMP     RTMP
}

type RTMP struct {
	ReconnectDelay int // Seconds to wait before cleaning up disconnected stream (default: 30)
	CleanupDelay   int // Seconds to wait before removing stream files (default: 30)
}
