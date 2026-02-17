package metrics

import (
	"strings"
	"testing"

	"Theatrum/domain/models"
	"Theatrum/domain/services"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

// testStorage implements repositories.StoragePort for testing.
type testStorage struct{}

func (s *testStorage) ReadFile(path string) ([]byte, error)     { return nil, nil }
func (s *testStorage) WriteFile(path string, data []byte) error { return nil }
func (s *testStorage) DeleteFile(path string) error             { return nil }
func (s *testStorage) ListFiles(pattern string) ([]string, error) {
	return nil, nil
}
func (s *testStorage) GetFileSize(path string) (int64, error) { return 0, nil }
func (s *testStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	return nil, nil, nil
}

func TestViewerCollector_Describe(t *testing.T) {
	tracker := services.NewViewerTracker(&testStorage{})
	collector := NewViewerCollector(tracker)

	ch := make(chan *prometheus.Desc, 10)
	collector.Describe(ch)
	close(ch)

	descs := make([]*prometheus.Desc, 0)
	for d := range ch {
		descs = append(descs, d)
	}
	if len(descs) != 2 {
		t.Fatalf("expected 2 descriptors, got %d", len(descs))
	}
}

func TestViewerCollector_NoStreams(t *testing.T) {
	tracker := services.NewViewerTracker(&testStorage{})
	collector := NewViewerCollector(tracker)

	count := testutil.CollectAndCount(collector)
	if count != 0 {
		t.Errorf("expected 0 metrics with no streams, got %d", count)
	}
}

func TestViewerCollector_ActiveStreams(t *testing.T) {
	tracker := services.NewViewerTracker(&testStorage{})
	collector := NewViewerCollector(tracker)

	// Use a window so that views are counted immediately on first request
	// (Window=1 means viewer needs 1s before counting, but views also need 1s)
	// Instead, register a stream and just verify the metrics structure is correct.
	// Views with window=0 count immediately since the condition is >=.
	viewersCfg := models.Viewers{Enabled: true, Window: 30}
	viewsCfg := models.Views{Enabled: true, Window: 0}

	tracker.TrackSegmentRequest("live/alice", "1.1.1.1", viewersCfg, viewsCfg)
	tracker.TrackSegmentRequest("live/alice", "2.2.2.2", viewersCfg, viewsCfg)

	// Viewers are 0 because window hasn't elapsed, views are 2 (window=0 counts immediately)
	expected := `
# HELP theatrum_stream_viewers Concurrent viewers per stream.
# TYPE theatrum_stream_viewers gauge
theatrum_stream_viewers{stream_path="live/alice"} 0
# HELP theatrum_stream_views Total accumulated views per stream.
# TYPE theatrum_stream_views gauge
theatrum_stream_views{stream_path="live/alice"} 2
`
	err := testutil.CollectAndCompare(collector, strings.NewReader(expected))
	if err != nil {
		t.Errorf("unexpected metrics:\n%s", err)
	}
}

func TestViewerCollector_AfterUnregister(t *testing.T) {
	tracker := services.NewViewerTracker(&testStorage{})
	collector := NewViewerCollector(tracker)

	viewersCfg := models.Viewers{Enabled: true, Window: 30}
	viewsCfg := models.Views{Enabled: true, Window: 0}

	tracker.TrackSegmentRequest("live/alice", "1.1.1.1", viewersCfg, viewsCfg)
	tracker.UnregisterStream("live/alice")

	count := testutil.CollectAndCount(collector)
	if count != 0 {
		t.Errorf("expected 0 metrics after unregister, got %d", count)
	}
}
