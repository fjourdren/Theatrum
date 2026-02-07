package services

import "sync"

// LiveStreamRegistry stores pre-resolved builtin template variables per stream session.
// This ensures that builtins like {%UUID%} and {%STARTING_DATE%} are generated once
// at RTMP publish time and reused consistently by HTTP handlers and on reconnection.
type LiveStreamRegistry struct {
	mu    sync.RWMutex
	paths map[string]map[string]string // streamKey -> builtinVars
}

func NewLiveStreamRegistry() *LiveStreamRegistry {
	return &LiveStreamRegistry{
		paths: make(map[string]map[string]string),
	}
}

// GetOrRegister atomically stores builtinVars for the given key if not already present.
// Returns the existing vars on reconnection, or the newly stored vars on first publish.
func (r *LiveStreamRegistry) GetOrRegister(key string, builtinVars map[string]string) map[string]string {
	r.mu.Lock()
	defer r.mu.Unlock()

	if existing, ok := r.paths[key]; ok {
		return existing
	}

	r.paths[key] = builtinVars
	return builtinVars
}

// GetBuiltinVars returns the stored builtin vars for a stream key, if any.
func (r *LiveStreamRegistry) GetBuiltinVars(key string) (map[string]string, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	vars, ok := r.paths[key]
	return vars, ok
}

// Unregister removes the builtin vars for a stream key (called after stream cleanup).
func (r *LiveStreamRegistry) Unregister(key string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	delete(r.paths, key)
}
