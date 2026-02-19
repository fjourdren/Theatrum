package metrics

import "github.com/prometheus/client_golang/prometheus"

// NewTestableMetrics creates a Metrics instance without registering collectors.
// Use this in tests to avoid duplicate registration errors.
func NewTestableMetrics() *Metrics {
	return &Metrics{
		HttpRequestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_http_requests_total",
		}, []string{"status_code", "stream_type", "file_type"}),
		HttpRequestDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "test_http_request_duration_seconds",
		}, []string{"stream_type", "file_type"}),
		HttpResponseBytes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_http_response_bytes_total",
		}, []string{"stream_type", "file_type"}),
		HttpRequestsInFlight: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "test_http_requests_in_flight",
		}),
		RtmpConnectionsTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "test_rtmp_connections_total",
		}),
		RtmpConnectionsActive: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "test_rtmp_connections_active",
		}),
		RtmpAuthTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_rtmp_auth_total",
		}, []string{"result"}),
		RtmpReceivedBytes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_rtmp_received_bytes_total",
		}, []string{"channel", "type", "stream_path"}),
		RtmpReceivedFrames: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_rtmp_received_frames_total",
		}, []string{"channel", "type", "stream_path"}),
		LiveStreamsActive: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "test_live_streams_active",
		}),
		StreamDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "test_stream_duration_seconds",
		}, []string{"stream_path"}),
		FfmpegExitsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_ffmpeg_exits_total",
		}, []string{"status", "stream_path"}),
		RecordingsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_recordings_total",
		}, []string{"mode", "status", "stream_path"}),
		EncodeQueueDepth: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "test_encode_queue_depth",
		}),
		EncodeJobsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_encode_jobs_total",
		}, []string{"status"}),
		EncodeJobDuration: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name: "test_encode_job_duration_seconds",
		}),
		ChannelsConfigured: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "test_channels_configured",
		}, []string{"type"}),
	}
}
