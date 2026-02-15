package metrics

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
)

// Metrics holds all Prometheus collectors for Theatrum.
type Metrics struct {
	// HTTP
	HttpRequestsTotal    *prometheus.CounterVec
	HttpRequestDuration  *prometheus.HistogramVec
	HttpResponseBytes    *prometheus.CounterVec
	HttpRequestsInFlight prometheus.Gauge

	// RTMP
	RtmpConnectionsTotal  prometheus.Counter
	RtmpConnectionsActive prometheus.Gauge
	RtmpAuthTotal         *prometheus.CounterVec
	RtmpReceivedBytes     *prometheus.CounterVec
	RtmpReceivedFrames    *prometheus.CounterVec

	// Live streams
	LiveStreamsActive prometheus.Gauge
	StreamDuration   prometheus.Histogram
	FfmpegExitsTotal *prometheus.CounterVec
	RecordingsTotal  *prometheus.CounterVec

	// Encoding
	EncodeQueueDepth  prometheus.Gauge
	EncodeJobsTotal   *prometheus.CounterVec
	EncodeJobDuration prometheus.Histogram

	// Config info
	ChannelsConfigured *prometheus.GaugeVec
}

// NewMetrics creates and registers all Prometheus metrics.
func NewMetrics() *Metrics {
	m := &Metrics{
		// HTTP
		HttpRequestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_http_requests_total",
			Help: "Total number of HTTP requests served.",
		}, []string{"status_code", "stream_type", "file_type"}),

		HttpRequestDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "theatrum_http_request_duration_seconds",
			Help:    "Duration of HTTP requests in seconds.",
			Buckets: prometheus.DefBuckets,
		}, []string{"stream_type", "file_type"}),

		HttpResponseBytes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_http_response_bytes_total",
			Help: "Total bytes sent in HTTP responses.",
		}, []string{"stream_type", "file_type"}),

		HttpRequestsInFlight: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "theatrum_http_requests_in_flight",
			Help: "Number of HTTP requests currently being served.",
		}),

		// RTMP
		RtmpConnectionsTotal: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "theatrum_rtmp_connections_total",
			Help: "Total number of RTMP connections.",
		}),

		RtmpConnectionsActive: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "theatrum_rtmp_connections_active",
			Help: "Number of currently active RTMP connections.",
		}),

		RtmpAuthTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_rtmp_auth_total",
			Help: "Total number of RTMP authentication attempts.",
		}, []string{"result"}),

		RtmpReceivedBytes: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_rtmp_received_bytes_total",
			Help: "Total bytes received from RTMP streams.",
		}, []string{"channel", "type"}),

		RtmpReceivedFrames: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_rtmp_received_frames_total",
			Help: "Total frames received from RTMP streams.",
		}, []string{"channel", "type"}),

		// Live streams
		LiveStreamsActive: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "theatrum_live_streams_active",
			Help: "Number of currently active live streams.",
		}),

		StreamDuration: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name:    "theatrum_stream_duration_seconds",
			Help:    "Duration of live streams in seconds.",
			Buckets: []float64{10, 30, 60, 300, 600, 1800, 3600, 7200, 14400},
		}),

		FfmpegExitsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_ffmpeg_exits_total",
			Help: "Total number of FFmpeg process exits.",
		}, []string{"status"}),

		RecordingsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_recordings_total",
			Help: "Total number of recording operations.",
		}, []string{"mode", "status"}),

		// Encoding
		EncodeQueueDepth: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "theatrum_encode_queue_depth",
			Help: "Number of jobs currently in the encode queue.",
		}),

		EncodeJobsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "theatrum_encode_jobs_total",
			Help: "Total number of encode jobs processed.",
		}, []string{"status"}),

		EncodeJobDuration: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name:    "theatrum_encode_job_duration_seconds",
			Help:    "Duration of encode jobs in seconds.",
			Buckets: []float64{1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600},
		}),

		// Config info
		ChannelsConfigured: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "theatrum_channels_configured",
			Help: "Number of configured channels by type.",
		}, []string{"type"}),
	}

	prometheus.MustRegister(
		m.HttpRequestsTotal,
		m.HttpRequestDuration,
		m.HttpResponseBytes,
		m.HttpRequestsInFlight,
		m.RtmpConnectionsTotal,
		m.RtmpConnectionsActive,
		m.RtmpAuthTotal,
		m.RtmpReceivedBytes,
		m.RtmpReceivedFrames,
		m.LiveStreamsActive,
		m.StreamDuration,
		m.FfmpegExitsTotal,
		m.RecordingsTotal,
		m.EncodeQueueDepth,
		m.EncodeJobsTotal,
		m.EncodeJobDuration,
		m.ChannelsConfigured,
	)

	return m
}

// ResponseWriter wraps http.ResponseWriter to capture status code and bytes written.
type ResponseWriter struct {
	http.ResponseWriter
	StatusCode   int
	BytesWritten int
}

// NewResponseWriter creates a new ResponseWriter wrapper.
func NewResponseWriter(w http.ResponseWriter) *ResponseWriter {
	return &ResponseWriter{
		ResponseWriter: w,
		StatusCode:     http.StatusOK,
	}
}

// WriteHeader captures the status code.
func (rw *ResponseWriter) WriteHeader(code int) {
	rw.StatusCode = code
	rw.ResponseWriter.WriteHeader(code)
}

// Write captures the number of bytes written.
func (rw *ResponseWriter) Write(b []byte) (int, error) {
	n, err := rw.ResponseWriter.Write(b)
	rw.BytesWritten += n
	return n, err
}
