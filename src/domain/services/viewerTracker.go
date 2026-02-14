package services

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"Theatrum/domain/models"
)

// ViewerTracker tracks concurrent viewers and total views per stream.
type ViewerTracker struct {
	mu      sync.RWMutex
	streams map[string]*streamTracking // trackingKey -> tracking data
}

type streamTracking struct {
	mu sync.RWMutex

	// Concurrent viewers (live only)
	viewersEnabled bool
	viewerWindow   time.Duration
	activeViewers  map[string]time.Time // clientIP -> lastSegmentRequest

	// Total views (all types)
	viewsEnabled bool
	viewWindow   time.Duration
	viewSessions map[string]time.Time // clientIP -> lastActivity
	totalViews   int64
}

func NewViewerTracker() *ViewerTracker {
	vt := &ViewerTracker{
		streams: make(map[string]*streamTracking),
	}
	go vt.cleanupLoop()
	return vt
}

// TrackSegmentRequest is called on every .ts request. Updates viewer activity
// and increments view count on new sessions.
func (vt *ViewerTracker) TrackSegmentRequest(trackingKey, clientIP string, viewersCfg models.Viewers, viewsCfg models.Views) {
	st := vt.getOrCreateStream(trackingKey, viewersCfg, viewsCfg)

	now := time.Now()
	st.mu.Lock()
	defer st.mu.Unlock()

	// Track concurrent viewers
	if st.viewersEnabled {
		st.activeViewers[clientIP] = now
	}

	// Track views
	if st.viewsEnabled {
		lastSeen, exists := st.viewSessions[clientIP]
		if !exists || now.Sub(lastSeen) >= st.viewWindow {
			st.totalViews++
		}
		st.viewSessions[clientIP] = now
	}
}

// GetViewerCount returns the number of active viewers for a stream.
func (vt *ViewerTracker) GetViewerCount(trackingKey string) int {
	vt.mu.RLock()
	st, ok := vt.streams[trackingKey]
	vt.mu.RUnlock()
	if !ok {
		return 0
	}

	now := time.Now()
	st.mu.RLock()
	defer st.mu.RUnlock()

	count := 0
	for _, lastSeen := range st.activeViewers {
		if now.Sub(lastSeen) < st.viewerWindow {
			count++
		}
	}
	return count
}

// GetViewCount returns the total accumulated views for a stream.
func (vt *ViewerTracker) GetViewCount(trackingKey string) int64 {
	vt.mu.RLock()
	st, ok := vt.streams[trackingKey]
	vt.mu.RUnlock()
	if !ok {
		return 0
	}

	st.mu.RLock()
	defer st.mu.RUnlock()
	return st.totalViews
}

// UnregisterStream cleans up tracking data when a live stream ends.
func (vt *ViewerTracker) UnregisterStream(trackingKey string) {
	vt.mu.Lock()
	defer vt.mu.Unlock()
	delete(vt.streams, trackingKey)
}

func (vt *ViewerTracker) getOrCreateStream(trackingKey string, viewersCfg models.Viewers, viewsCfg models.Views) *streamTracking {
	vt.mu.RLock()
	st, ok := vt.streams[trackingKey]
	vt.mu.RUnlock()
	if ok {
		return st
	}

	vt.mu.Lock()
	defer vt.mu.Unlock()

	// Double-check after acquiring write lock
	if st, ok := vt.streams[trackingKey]; ok {
		return st
	}

	st = &streamTracking{
		viewersEnabled: viewersCfg.Enabled,
		viewerWindow:   time.Duration(viewersCfg.Window) * time.Second,
		activeViewers:  make(map[string]time.Time),
		viewsEnabled:   viewsCfg.Enabled,
		viewWindow:     time.Duration(viewsCfg.Window) * time.Second,
		viewSessions:   make(map[string]time.Time),
	}
	vt.streams[trackingKey] = st
	return st
}

// cleanupLoop periodically removes expired viewer entries.
func (vt *ViewerTracker) cleanupLoop() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		vt.mu.RLock()
		keys := make([]string, 0, len(vt.streams))
		for k := range vt.streams {
			keys = append(keys, k)
		}
		vt.mu.RUnlock()

		now := time.Now()
		for _, key := range keys {
			vt.mu.RLock()
			st, ok := vt.streams[key]
			vt.mu.RUnlock()
			if !ok {
				continue
			}

			st.mu.Lock()
			for ip, lastSeen := range st.activeViewers {
				if now.Sub(lastSeen) >= st.viewerWindow {
					delete(st.activeViewers, ip)
				}
			}
			st.mu.Unlock()
		}
	}
}

// GetClientIP extracts the client IP from an HTTP request.
// Checks X-Forwarded-For header first for reverse proxy support.
func GetClientIP(r *http.Request) string {
	// Check X-Forwarded-For header
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		// Take the first IP (original client)
		if idx := strings.IndexByte(xff, ','); idx != -1 {
			return strings.TrimSpace(xff[:idx])
		}
		return strings.TrimSpace(xff)
	}

	// Fall back to RemoteAddr (strip port)
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return ip
}
