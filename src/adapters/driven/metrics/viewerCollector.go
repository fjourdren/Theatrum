package metrics

import (
	"Theatrum/domain/services"

	"github.com/prometheus/client_golang/prometheus"
)

var (
	viewersDesc = prometheus.NewDesc(
		"theatrum_stream_viewers",
		"Concurrent viewers per stream.",
		[]string{"stream_path"}, nil,
	)
	viewsDesc = prometheus.NewDesc(
		"theatrum_stream_views",
		"Total accumulated views per stream.",
		[]string{"stream_path"}, nil,
	)
)

// ViewerCollector implements prometheus.Collector by querying ViewerTracker on each scrape.
type ViewerCollector struct {
	tracker *services.ViewerTracker
}

// NewViewerCollector creates a new ViewerCollector.
func NewViewerCollector(tracker *services.ViewerTracker) *ViewerCollector {
	return &ViewerCollector{tracker: tracker}
}

// Describe sends the metric descriptors to the channel.
func (c *ViewerCollector) Describe(ch chan<- *prometheus.Desc) {
	ch <- viewersDesc
	ch <- viewsDesc
}

// Collect queries the tracker and emits one gauge per active stream.
func (c *ViewerCollector) Collect(ch chan<- prometheus.Metric) {
	for _, s := range c.tracker.GetAllStreamStats() {
		ch <- prometheus.MustNewConstMetric(viewersDesc, prometheus.GaugeValue, float64(s.Viewers), s.TrackingKey)
		ch <- prometheus.MustNewConstMetric(viewsDesc, prometheus.GaugeValue, float64(s.Views), s.TrackingKey)
	}
}
