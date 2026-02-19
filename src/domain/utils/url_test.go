package utils

import "testing"

func TestJoinURL(t *testing.T) {
	tests := []struct {
		name   string
		base   string
		parts  []string
		expect string
	}{
		{
			"base only",
			"http://localhost:8080",
			nil,
			"http://localhost:8080",
		},
		{
			"base with single part",
			"http://localhost:8080",
			[]string{"streams"},
			"http://localhost:8080/streams",
		},
		{
			"base with multiple parts",
			"http://localhost:8080",
			[]string{"api", "v1", "streams"},
			"http://localhost:8080/api/v1/streams",
		},
		{
			"trailing slash on base",
			"http://localhost:8080/",
			[]string{"streams"},
			"http://localhost:8080/streams",
		},
		{
			"leading slash on parts",
			"http://localhost:8080",
			[]string{"/streams/"},
			"http://localhost:8080/streams",
		},
		{
			"empty parts skipped",
			"http://localhost:8080",
			[]string{"", "streams", "", "live"},
			"http://localhost:8080/streams/live",
		},
		{
			"protocol preserved",
			"https://example.com",
			[]string{"path"},
			"https://example.com/path",
		},
		{
			"no protocol",
			"/base",
			[]string{"path"},
			"/base/path",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := JoinURL(tt.base, tt.parts...)
			if got != tt.expect {
				t.Errorf("JoinURL(%q, %v) = %q, want %q", tt.base, tt.parts, got, tt.expect)
			}
		})
	}
}
