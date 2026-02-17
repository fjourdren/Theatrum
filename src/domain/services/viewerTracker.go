package services

import (
	"log"
	"net"
	"net/http"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"

	"Theatrum/constants"
	"Theatrum/domain/models"
	"Theatrum/domain/repositories"
)

// ViewerTracker tracks concurrent viewers and total views per stream.
type ViewerTracker struct {
	mu      sync.RWMutex
	streams map[string]*streamTracking // trackingKey -> tracking data
	storage repositories.StoragePort
}

type viewerSession struct {
	startedAt  time.Time
	lastActive time.Time
}

type viewSession struct {
	startedAt  time.Time
	lastActive time.Time
	counted    bool // true once counted as a view
}

type streamTracking struct {
	mu sync.RWMutex

	// Concurrent viewers (live only)
	viewersEnabled bool
	viewerWindow   time.Duration
	activeViewers  map[string]*viewerSession // clientIP -> session

	// Total views (all types)
	viewsEnabled bool
	viewWindow   time.Duration
	viewSessions map[string]*viewSession // clientIP -> session
	totalViews   int64
	dirty        bool // true when totalViews changed since last save
}

func NewViewerTracker(storage repositories.StoragePort) *ViewerTracker {
	vt := &ViewerTracker{
		streams: make(map[string]*streamTracking),
		storage: storage,
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
		vs, exists := st.activeViewers[clientIP]
		if !exists || now.Sub(vs.lastActive) >= st.viewerWindow {
			vs = &viewerSession{startedAt: now}
			st.activeViewers[clientIP] = vs
		}
		vs.lastActive = now
	}

	// Track views
	if st.viewsEnabled {
		sess, exists := st.viewSessions[clientIP]
		if !exists || now.Sub(sess.lastActive) >= st.viewWindow {
			sess = &viewSession{startedAt: now}
			st.viewSessions[clientIP] = sess
		}
		sess.lastActive = now
		if !sess.counted && now.Sub(sess.startedAt) >= st.viewWindow {
			st.totalViews++
			st.dirty = true
			sess.counted = true
		}
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
	for _, vs := range st.activeViewers {
		if now.Sub(vs.startedAt) >= st.viewerWindow && now.Sub(vs.lastActive) < st.viewerWindow {
			count++
		}
	}
	return count
}

// GetViewCount returns the total accumulated views for a stream.
// Falls back to reading from disk when the stream is not in memory.
func (vt *ViewerTracker) GetViewCount(trackingKey string) int64 {
	vt.mu.RLock()
	st, ok := vt.streams[trackingKey]
	vt.mu.RUnlock()
	if !ok {
		return vt.loadViewCount(trackingKey)
	}

	st.mu.RLock()
	defer st.mu.RUnlock()
	return st.totalViews
}

// StreamStats holds viewer and view counts for a single stream.
type StreamStats struct {
	TrackingKey string
	Viewers     int
	Views       int64
}

// GetAllStreamStats returns stats for all active streams.
// Used by the Prometheus collector to export metrics on each scrape.
func (vt *ViewerTracker) GetAllStreamStats() []StreamStats {
	vt.mu.RLock()
	keys := make([]string, 0, len(vt.streams))
	for k := range vt.streams {
		keys = append(keys, k)
	}
	vt.mu.RUnlock()

	now := time.Now()
	stats := make([]StreamStats, 0, len(keys))

	for _, key := range keys {
		vt.mu.RLock()
		st, ok := vt.streams[key]
		vt.mu.RUnlock()
		if !ok {
			continue
		}

		st.mu.RLock()
		viewers := 0
		if st.viewersEnabled {
			for _, vs := range st.activeViewers {
				if now.Sub(vs.startedAt) >= st.viewerWindow && now.Sub(vs.lastActive) < st.viewerWindow {
					viewers++
				}
			}
		}
		views := st.totalViews
		st.mu.RUnlock()

		stats = append(stats, StreamStats{
			TrackingKey: key,
			Viewers:     viewers,
			Views:       views,
		})
	}

	return stats
}

// UnregisterStream cleans up tracking data when a live stream ends.
// Persists the final view count to disk before removing from memory.
func (vt *ViewerTracker) UnregisterStream(trackingKey string) {
	vt.mu.Lock()
	st, ok := vt.streams[trackingKey]
	if ok {
		delete(vt.streams, trackingKey)
	}
	vt.mu.Unlock()

	if ok && st.viewsEnabled {
		st.mu.RLock()
		count := st.totalViews
		st.mu.RUnlock()
		vt.saveViewCount(trackingKey, count)
	}
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
		activeViewers:  make(map[string]*viewerSession),
		viewsEnabled:   viewsCfg.Enabled,
		viewWindow:     time.Duration(viewsCfg.Window) * time.Second,
		viewSessions:   make(map[string]*viewSession),
		totalViews:     vt.loadViewCount(trackingKey),
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

		type dirtyEntry struct {
			key   string
			count int64
		}
		var toSave []dirtyEntry

		now := time.Now()
		for _, key := range keys {
			vt.mu.RLock()
			st, ok := vt.streams[key]
			vt.mu.RUnlock()
			if !ok {
				continue
			}

			st.mu.Lock()
			for ip, vs := range st.activeViewers {
				if now.Sub(vs.lastActive) >= st.viewerWindow {
					delete(st.activeViewers, ip)
				}
			}
			for ip, sess := range st.viewSessions {
				if now.Sub(sess.lastActive) >= st.viewWindow {
					delete(st.viewSessions, ip)
				}
			}
			if st.dirty && st.viewsEnabled {
				toSave = append(toSave, dirtyEntry{key: key, count: st.totalViews})
				st.dirty = false
			}
			st.mu.Unlock()
		}

		// Save dirty view counts outside of locks
		for _, entry := range toSave {
			vt.saveViewCount(entry.key, entry.count)
		}
	}
}

// viewsFilePath returns the disk path for persisted view counts.
func viewsFilePath(trackingKey string) string {
	return path.Join(constants.VideoDir, trackingKey, constants.ViewsFile)
}

// loadViewCount reads a persisted view count from disk. Returns 0 if not found.
func (vt *ViewerTracker) loadViewCount(trackingKey string) int64 {
	data, err := vt.storage.ReadFile(viewsFilePath(trackingKey))
	if err != nil {
		return 0
	}
	count, err := strconv.ParseInt(strings.TrimSpace(string(data)), 10, 64)
	if err != nil {
		return 0
	}
	return count
}

// saveViewCount writes a view count to disk.
func (vt *ViewerTracker) saveViewCount(trackingKey string, count int64) {
	if err := vt.storage.WriteFile(viewsFilePath(trackingKey), []byte(strconv.FormatInt(count, 10))); err != nil {
		log.Printf("Error persisting view count for %s: %v", trackingKey, err)
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
