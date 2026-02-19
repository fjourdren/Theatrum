package constants

var (
	DefaultQuality                = "default"
	MasterPlaylist                = "master.m3u8"
	SubPlaylist                   = "playlist.m3u8"
	SegmentName                   = "segment_%03d.ts"
	DashManifest                  = "manifest.mpd"
	DashInitSegName               = "init-stream$RepresentationID$.m4s"
	DashSegName                   = "chunk-stream$RepresentationID$-$Number%05d$.m4s"
	ViewersFile                   = "viewers.txt"
	ViewsFile                     = "views.txt"
	ValidVideoExtensions          = []string{".mp4"}
	ValidMasterPlaylistExtensions = []string{".m3u8"}
)