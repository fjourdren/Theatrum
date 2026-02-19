package metrics

type EncodeMetricsAdapter struct {
	metrics *Metrics
}

func NewEncodeMetricsAdapter(m *Metrics) *EncodeMetricsAdapter {
	return &EncodeMetricsAdapter{metrics: m}
}

func (a *EncodeMetricsAdapter) SetEncodeQueueDepth(depth float64) {
	a.metrics.EncodeQueueDepth.Set(depth)
}

func (a *EncodeMetricsAdapter) ObserveEncodeJobDuration(seconds float64) {
	a.metrics.EncodeJobDuration.Observe(seconds)
}

func (a *EncodeMetricsAdapter) IncEncodeJobsTotal(status string) {
	a.metrics.EncodeJobsTotal.WithLabelValues(status).Inc()
}
