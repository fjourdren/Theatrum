//go:build !e2e

package models

import (
	"testing"
)

func TestDistribution_HlsEnabled(t *testing.T) {
	tests := []struct {
		name     string
		dist     Distribution
		expected bool
	}{
		{"hls configured", Distribution{Hls: &Hls{SegmentDuration: 2}}, true},
		{"hls nil", Distribution{Hls: nil}, false},
		{"dash only", Distribution{Dash: &Dash{SegmentDuration: 2}}, false},
		{"dual mode", Distribution{Hls: &Hls{SegmentDuration: 2}, Dash: &Dash{SegmentDuration: 2}}, true},
		{"empty distribution", Distribution{}, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := tt.dist.HlsEnabled()
			if got != tt.expected {
				t.Errorf("HlsEnabled() = %v, want %v", got, tt.expected)
			}
		})
	}
}

func TestDistribution_DashEnabled(t *testing.T) {
	tests := []struct {
		name     string
		dist     Distribution
		expected bool
	}{
		{"dash configured", Distribution{Dash: &Dash{SegmentDuration: 4}}, true},
		{"dash nil", Distribution{Dash: nil}, false},
		{"hls only", Distribution{Hls: &Hls{SegmentDuration: 2}}, false},
		{"dual mode", Distribution{Hls: &Hls{SegmentDuration: 2}, Dash: &Dash{SegmentDuration: 2}}, true},
		{"empty distribution", Distribution{}, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := tt.dist.DashEnabled()
			if got != tt.expected {
				t.Errorf("DashEnabled() = %v, want %v", got, tt.expected)
			}
		})
	}
}

func TestDistribution_IsDualMode(t *testing.T) {
	tests := []struct {
		name     string
		dist     Distribution
		expected bool
	}{
		{"both enabled", Distribution{Hls: &Hls{SegmentDuration: 2}, Dash: &Dash{SegmentDuration: 2}}, true},
		{"hls only", Distribution{Hls: &Hls{SegmentDuration: 2}}, false},
		{"dash only", Distribution{Dash: &Dash{SegmentDuration: 4}}, false},
		{"neither", Distribution{}, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := tt.dist.IsDualMode()
			if got != tt.expected {
				t.Errorf("IsDualMode() = %v, want %v", got, tt.expected)
			}
		})
	}
}

func TestDistribution_SegmentDuration(t *testing.T) {
	tests := []struct {
		name     string
		dist     Distribution
		expected int
	}{
		{"hls only", Distribution{Hls: &Hls{SegmentDuration: 6}}, 6},
		{"dash only", Distribution{Dash: &Dash{SegmentDuration: 4}}, 4},
		{"dual mode returns hls value", Distribution{Hls: &Hls{SegmentDuration: 2}, Dash: &Dash{SegmentDuration: 2}}, 2},
		{"neither returns zero", Distribution{}, 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := tt.dist.SegmentDuration()
			if got != tt.expected {
				t.Errorf("SegmentDuration() = %d, want %d", got, tt.expected)
			}
		})
	}
}

func TestDistribution_WindowSize(t *testing.T) {
	tests := []struct {
		name     string
		dist     Distribution
		expected int
	}{
		{"hls only", Distribution{Hls: &Hls{SegmentDuration: 2, WindowSize: 5}}, 5},
		{"dash only", Distribution{Dash: &Dash{SegmentDuration: 4, WindowSize: 7}}, 7},
		{"dual mode returns hls value", Distribution{Hls: &Hls{SegmentDuration: 2, WindowSize: 4}, Dash: &Dash{SegmentDuration: 2, WindowSize: 4}}, 4},
		{"neither returns default 3", Distribution{}, 3},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := tt.dist.WindowSize()
			if got != tt.expected {
				t.Errorf("WindowSize() = %d, want %d", got, tt.expected)
			}
		})
	}
}
