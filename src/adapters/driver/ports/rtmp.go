package ports

type RtmpPort interface {
	StartRtmpServer() error
	ShutdownRtmpServer() error
	GetActiveStreams() []string
}
