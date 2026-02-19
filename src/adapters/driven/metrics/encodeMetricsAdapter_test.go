package metrics

import (
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
)

func newTestMetrics() *Metrics {
	return &Metrics{
		EncodeQueueDepth: prometheus.NewGauge(prometheus.GaugeOpts{
			Name: "test_encode_queue_depth",
		}),
		EncodeJobDuration: prometheus.NewHistogram(prometheus.HistogramOpts{
			Name: "test_encode_job_duration",
		}),
		EncodeJobsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "test_encode_jobs_total",
		}, []string{"status"}),
	}
}

func TestEncodeMetricsAdapter_SetEncodeQueueDepth(t *testing.T) {
	m := newTestMetrics()
	adapter := NewEncodeMetricsAdapter(m)

	adapter.SetEncodeQueueDepth(5.0)

	var metric dto.Metric
	if err := m.EncodeQueueDepth.Write(&metric); err != nil {
		t.Fatalf("failed to read metric: %v", err)
	}
	if metric.GetGauge().GetValue() != 5.0 {
		t.Errorf("expected gauge value 5.0, got %f", metric.GetGauge().GetValue())
	}
}

func TestEncodeMetricsAdapter_ObserveEncodeJobDuration(t *testing.T) {
	m := newTestMetrics()
	adapter := NewEncodeMetricsAdapter(m)

	// Should not panic
	adapter.ObserveEncodeJobDuration(1.5)
	adapter.ObserveEncodeJobDuration(3.0)
}

func TestEncodeMetricsAdapter_IncEncodeJobsTotal(t *testing.T) {
	m := newTestMetrics()
	adapter := NewEncodeMetricsAdapter(m)

	// Should not panic
	adapter.IncEncodeJobsTotal("success")
	adapter.IncEncodeJobsTotal("failure")
	adapter.IncEncodeJobsTotal("success")
}
