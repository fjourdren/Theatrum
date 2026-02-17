package services

import (
	"testing"
	"time"

	"Theatrum/domain/models"
)

// mockStorage implements repositories.StoragePort for testing.
type mockStorage struct{}

func (m *mockStorage) ReadFile(path string) ([]byte, error)                          { return nil, nil }
func (m *mockStorage) WriteFile(path string, data []byte) error                      { return nil }
func (m *mockStorage) DeleteFile(path string) error                                  { return nil }
func (m *mockStorage) ListFiles(pattern string) ([]string, error)                    { return nil, nil }
func (m *mockStorage) GetFileSize(path string) (int64, error)                        { return 0, nil }
func (m *mockStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	return nil, nil, nil
}

// newTestTracker creates a ViewerTracker without starting the cleanup goroutine.
func newTestTracker() *ViewerTracker {
	return &ViewerTracker{
		streams: make(map[string]*streamTracking),
		storage: &mockStorage{},
	}
}

// makeActiveViewer creates a viewerSession that satisfies the counting condition:
// startedAt >= window ago AND lastActive < window ago.
func makeActiveViewer(window time.Duration) *viewerSession {
	now := time.Now()
	return &viewerSession{
		startedAt:  now.Add(-2 * window),
		lastActive: now,
	}
}

func TestViewerTracker_GetAllStreamStats_Empty(t *testing.T) {
	vt := newTestTracker()
	stats := vt.GetAllStreamStats()
	if len(stats) != 0 {
		t.Errorf("expected empty slice, got %d entries", len(stats))
	}
}

func TestViewerTracker_GetAllStreamStats_SingleStream(t *testing.T) {
	vt := newTestTracker()
	window := 5 * time.Second

	st := &streamTracking{
		viewersEnabled: true,
		viewerWindow:   window,
		activeViewers: map[string]*viewerSession{
			"1.1.1.1": makeActiveViewer(window),
			"2.2.2.2": makeActiveViewer(window),
		},
		viewsEnabled: true,
		viewWindow:   window,
		viewSessions: map[string]*viewSession{},
		totalViews:   10,
	}
	vt.mu.Lock()
	vt.streams["live/alice"] = st
	vt.mu.Unlock()

	stats := vt.GetAllStreamStats()
	if len(stats) != 1 {
		t.Fatalf("expected 1 stream, got %d", len(stats))
	}
	if stats[0].TrackingKey != "live/alice" {
		t.Errorf("expected tracking key 'live/alice', got %q", stats[0].TrackingKey)
	}
	if stats[0].Viewers != 2 {
		t.Errorf("expected 2 viewers, got %d", stats[0].Viewers)
	}
	if stats[0].Views != 10 {
		t.Errorf("expected 10 views, got %d", stats[0].Views)
	}
}

func TestViewerTracker_GetAllStreamStats_MultipleStreams(t *testing.T) {
	vt := newTestTracker()
	window := 5 * time.Second

	aliceSt := &streamTracking{
		viewersEnabled: true,
		viewerWindow:   window,
		activeViewers: map[string]*viewerSession{
			"1.1.1.1": makeActiveViewer(window),
		},
		viewsEnabled: true,
		viewWindow:   window,
		viewSessions: map[string]*viewSession{},
		totalViews:   3,
	}
	bobSt := &streamTracking{
		viewersEnabled: true,
		viewerWindow:   window,
		activeViewers: map[string]*viewerSession{
			"2.2.2.2": makeActiveViewer(window),
			"3.3.3.3": makeActiveViewer(window),
		},
		viewsEnabled: true,
		viewWindow:   window,
		viewSessions: map[string]*viewSession{},
		totalViews:   7,
	}
	vt.mu.Lock()
	vt.streams["live/alice"] = aliceSt
	vt.streams["live/bob"] = bobSt
	vt.mu.Unlock()

	stats := vt.GetAllStreamStats()
	if len(stats) != 2 {
		t.Fatalf("expected 2 streams, got %d", len(stats))
	}

	byKey := make(map[string]StreamStats)
	for _, s := range stats {
		byKey[s.TrackingKey] = s
	}

	alice := byKey["live/alice"]
	if alice.Viewers != 1 || alice.Views != 3 {
		t.Errorf("alice: expected 1 viewer / 3 views, got %d / %d", alice.Viewers, alice.Views)
	}
	bob := byKey["live/bob"]
	if bob.Viewers != 2 || bob.Views != 7 {
		t.Errorf("bob: expected 2 viewers / 7 views, got %d / %d", bob.Viewers, bob.Views)
	}
}

func TestViewerTracker_GetAllStreamStats_WindowThreshold(t *testing.T) {
	vt := newTestTracker()

	// 1-second window: viewer must watch for 1s before being counted
	viewersCfg := models.Viewers{Enabled: true, Window: 1}
	viewsCfg := models.Views{Enabled: true, Window: 1}

	vt.TrackSegmentRequest("live/alice", "1.1.1.1", viewersCfg, viewsCfg)

	stats := vt.GetAllStreamStats()
	if len(stats) != 1 {
		t.Fatalf("expected 1 stream, got %d", len(stats))
	}
	// Viewer just started, hasn't met the window threshold yet
	if stats[0].Viewers != 0 {
		t.Errorf("expected 0 viewers before window elapsed, got %d", stats[0].Viewers)
	}
	if stats[0].Views != 0 {
		t.Errorf("expected 0 views before window elapsed, got %d", stats[0].Views)
	}
}

func TestViewerTracker_GetAllStreamStats_WindowThreshold_Elapsed(t *testing.T) {
	vt := newTestTracker()
	window := 1 * time.Second

	st := &streamTracking{
		viewersEnabled: true,
		viewerWindow:   window,
		activeViewers: map[string]*viewerSession{
			"1.1.1.1": {
				startedAt:  time.Now().Add(-2 * time.Second),
				lastActive: time.Now(),
			},
		},
		viewsEnabled: true,
		viewWindow:   window,
		viewSessions: map[string]*viewSession{},
		totalViews:   5,
	}
	vt.mu.Lock()
	vt.streams["live/alice"] = st
	vt.mu.Unlock()

	stats := vt.GetAllStreamStats()
	if len(stats) != 1 {
		t.Fatalf("expected 1 stream, got %d", len(stats))
	}
	if stats[0].Viewers != 1 {
		t.Errorf("expected 1 viewer after window elapsed, got %d", stats[0].Viewers)
	}
	if stats[0].Views != 5 {
		t.Errorf("expected 5 views, got %d", stats[0].Views)
	}
}

func TestViewerTracker_GetAllStreamStats_ViewersDisabled(t *testing.T) {
	vt := newTestTracker()

	st := &streamTracking{
		viewersEnabled: false,
		viewerWindow:   0,
		activeViewers:  map[string]*viewerSession{},
		viewsEnabled:   true,
		viewWindow:     5 * time.Second,
		viewSessions:   map[string]*viewSession{},
		totalViews:     42,
	}
	vt.mu.Lock()
	vt.streams["live/alice"] = st
	vt.mu.Unlock()

	stats := vt.GetAllStreamStats()
	if len(stats) != 1 {
		t.Fatalf("expected 1 stream, got %d", len(stats))
	}
	if stats[0].Viewers != 0 {
		t.Errorf("expected 0 viewers when disabled, got %d", stats[0].Viewers)
	}
	if stats[0].Views != 42 {
		t.Errorf("expected 42 views, got %d", stats[0].Views)
	}
}

func TestViewerTracker_GetAllStreamStats_AfterUnregister(t *testing.T) {
	vt := newTestTracker()

	viewersCfg := models.Viewers{Enabled: true, Window: 1}
	viewsCfg := models.Views{Enabled: true, Window: 1}

	vt.TrackSegmentRequest("live/alice", "1.1.1.1", viewersCfg, viewsCfg)
	vt.UnregisterStream("live/alice")

	stats := vt.GetAllStreamStats()
	if len(stats) != 0 {
		t.Errorf("expected empty slice after unregister, got %d entries", len(stats))
	}
}
