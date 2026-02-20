package services

import (
	"encoding/hex"
	"testing"

	"Theatrum/domain/models"
)

func newTestRtmpAuthService(channels map[string]models.Stream) *RtmpAuthService {
	return NewRtmpAuthService(&channels, NewPathTemplateService())
}

func TestRtmpAuthService_IsAuthorized(t *testing.T) {
	channels := map[string]models.Stream{
		"/user/{username}":          {Type: models.StreamTypeLive},
		"/premium/{room_id}/{user}": {Type: models.StreamTypeLive},
	}
	svc := newTestRtmpAuthService(channels)

	tests := []struct {
		name   string
		tcurl  string
		expect bool
	}{
		{"matching single var", "rtmp://localhost/user/alice", true},
		{"matching multi var", "rtmp://localhost/premium/42/bob", true},
		{"no match", "rtmp://localhost/unknown/foo", false},
		{"partial match", "rtmp://localhost/user/", false},
		{"empty tcurl", "", false},
		{"just hostname", "rtmp://localhost", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := svc.IsAuthorized(tt.tcurl)
			if got != tt.expect {
				t.Errorf("IsAuthorized(%q) = %v, want %v", tt.tcurl, got, tt.expect)
			}
		})
	}
}

func TestRtmpAuthService_ExtractChannel(t *testing.T) {
	channels := map[string]models.Stream{
		"/user/{username}":          {Type: models.StreamTypeLive, Path: "live/{username}"},
		"/premium/{room_id}/{user}": {Type: models.StreamTypeLive, Path: "premium/{room_id}/{user}"},
	}
	svc := newTestRtmpAuthService(channels)

	t.Run("single variable extraction", func(t *testing.T) {
		stream, vars, pattern, ok := svc.ExtractChannel("rtmp://localhost/user/alice")
		if !ok {
			t.Fatal("expected match")
		}
		if stream == nil {
			t.Fatal("expected non-nil stream")
		}
		if vars["username"] != "alice" {
			t.Errorf("expected username=alice, got %q", vars["username"])
		}
		if pattern != "/user/{username}" {
			t.Errorf("expected pattern /user/{username}, got %q", pattern)
		}
	})

	t.Run("multi variable extraction", func(t *testing.T) {
		stream, vars, _, ok := svc.ExtractChannel("rtmp://localhost/premium/42/bob")
		if !ok {
			t.Fatal("expected match")
		}
		if stream == nil {
			t.Fatal("expected non-nil stream")
		}
		if vars["room_id"] != "42" {
			t.Errorf("expected room_id=42, got %q", vars["room_id"])
		}
		if vars["user"] != "bob" {
			t.Errorf("expected user=bob, got %q", vars["user"])
		}
	})

	t.Run("no match", func(t *testing.T) {
		stream, vars, pattern, ok := svc.ExtractChannel("rtmp://localhost/unknown/foo")
		if ok {
			t.Error("expected no match")
		}
		if stream != nil {
			t.Error("expected nil stream")
		}
		if vars != nil {
			t.Error("expected nil vars")
		}
		if pattern != "" {
			t.Errorf("expected empty pattern, got %q", pattern)
		}
	})
}

func TestRtmpAuthService_ValidateAuthentication(t *testing.T) {
	channels := map[string]models.Stream{}
	svc := newTestRtmpAuthService(channels)

	stream := &models.Stream{
		LiveStreamKey:     "secretkey",
		AuthTokenTemplate: "{username}",
	}

	// Compute expected token: XOR("alice", "secretkey")
	expectedToken := svc.xorString("secretkey", "alice")

	tests := []struct {
		name           string
		vars           map[string]string
		publishingName string
		wantErr        bool
	}{
		{"valid token", map[string]string{"username": "alice"}, expectedToken, false},
		{"invalid token", map[string]string{"username": "alice"}, "wrongtoken", true},
		{"empty publishingName", map[string]string{"username": "alice"}, "", true},
		{"missing vars", map[string]string{}, expectedToken, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := svc.ValidateAuthentication(stream, tt.vars, tt.publishingName)
			if (err != nil) != tt.wantErr {
				t.Errorf("ValidateAuthentication() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}

func TestRtmpAuthService_ValidateAuthentication_MultiVariable(t *testing.T) {
	channels := map[string]models.Stream{}
	svc := newTestRtmpAuthService(channels)

	stream := &models.Stream{
		LiveStreamKey:     "secretkey",
		AuthTokenTemplate: "{room_id}{username}",
	}

	// XOR input is "42alice" (room_id + username concatenated)
	expectedToken := svc.xorString("secretkey", "42alice")

	t.Run("valid multi-variable token", func(t *testing.T) {
		vars := map[string]string{"room_id": "42", "username": "alice"}
		err := svc.ValidateAuthentication(stream, vars, expectedToken)
		if err != nil {
			t.Errorf("expected valid authentication, got error: %v", err)
		}
	})

	t.Run("wrong variable values", func(t *testing.T) {
		vars := map[string]string{"room_id": "99", "username": "alice"}
		err := svc.ValidateAuthentication(stream, vars, expectedToken)
		if err == nil {
			t.Error("expected error for wrong variable values")
		}
	})

	t.Run("partial variables missing", func(t *testing.T) {
		vars := map[string]string{"username": "alice"}
		err := svc.ValidateAuthentication(stream, vars, expectedToken)
		if err == nil {
			t.Error("expected error when room_id is missing")
		}
	})
}

func TestRtmpAuthService_BuildStreamPath(t *testing.T) {
	channels := map[string]models.Stream{}
	svc := newTestRtmpAuthService(channels)

	stream := &models.Stream{Path: "live/{username}"}
	vars := map[string]string{"username": "alice"}

	got, err := svc.BuildStreamPath(stream, vars)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != "live/alice" {
		t.Errorf("expected live/alice, got %q", got)
	}
}

func TestRtmpAuthService_xorString(t *testing.T) {
	channels := map[string]models.Stream{}
	svc := newTestRtmpAuthService(channels)

	tests := []struct {
		name   string
		key    string
		input  string
		expect string
	}{
		{
			"basic XOR",
			"key",
			"abc",
			hex.EncodeToString([]byte{
				'a' ^ 'k',
				'b' ^ 'e',
				'c' ^ 'y',
			}),
		},
		{
			"key cycling",
			"ab",
			"wxyz",
			hex.EncodeToString([]byte{
				'w' ^ 'a',
				'x' ^ 'b',
				'y' ^ 'a',
				'z' ^ 'b',
			}),
		},
		{
			"single char key",
			"x",
			"hello",
			hex.EncodeToString([]byte{
				'h' ^ 'x',
				'e' ^ 'x',
				'l' ^ 'x',
				'l' ^ 'x',
				'o' ^ 'x',
			}),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := svc.xorString(tt.key, tt.input)
			if got != tt.expect {
				t.Errorf("xorString(%q, %q) = %q, want %q", tt.key, tt.input, got, tt.expect)
			}
		})
	}
}

func TestRtmpAuthService_patternToRegex(t *testing.T) {
	channels := map[string]models.Stream{}
	svc := newTestRtmpAuthService(channels)

	tests := []struct {
		name         string
		pattern      string
		wantVarCount int
		shouldMatch  string
		shouldFail   string
	}{
		{
			"single variable",
			"/user/{username}",
			1,
			"/user/alice",
			"/user/alice/extra",
		},
		{
			"multiple variables",
			"/room/{room_id}/{user}",
			2,
			"/room/42/bob",
			"/room/42",
		},
		{
			"no variables",
			"/static/path",
			0,
			"/static/path",
			"/static/other",
		},
		{
			"special regex chars in pattern",
			"/path.with.dots/{id}",
			1,
			"/path.with.dots/123",
			"/pathXwithXdots/123",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			regexStr, varNames := svc.patternToRegex(tt.pattern)
			if len(varNames) != tt.wantVarCount {
				t.Errorf("expected %d vars, got %d", tt.wantVarCount, len(varNames))
			}

			_, ok := svc.extractVariables(regexStr, varNames, tt.shouldMatch)
			if !ok {
				t.Errorf("expected %q to match pattern %q", tt.shouldMatch, tt.pattern)
			}

			_, ok = svc.extractVariables(regexStr, varNames, tt.shouldFail)
			if ok {
				t.Errorf("expected %q not to match pattern %q", tt.shouldFail, tt.pattern)
			}
		})
	}
}

func TestRtmpAuthService_extractVariables(t *testing.T) {
	channels := map[string]models.Stream{}
	svc := newTestRtmpAuthService(channels)

	t.Run("match with variables", func(t *testing.T) {
		regexStr, varNames := svc.patternToRegex("/user/{username}")
		vars, ok := svc.extractVariables(regexStr, varNames, "/user/alice")
		if !ok {
			t.Fatal("expected match")
		}
		if vars["username"] != "alice" {
			t.Errorf("expected username=alice, got %q", vars["username"])
		}
	})

	t.Run("no match", func(t *testing.T) {
		regexStr, varNames := svc.patternToRegex("/user/{username}")
		_, ok := svc.extractVariables(regexStr, varNames, "/other/path")
		if ok {
			t.Error("expected no match")
		}
	})

	t.Run("multiple variables", func(t *testing.T) {
		regexStr, varNames := svc.patternToRegex("/room/{room_id}/{user}")
		vars, ok := svc.extractVariables(regexStr, varNames, "/room/42/bob")
		if !ok {
			t.Fatal("expected match")
		}
		if vars["room_id"] != "42" {
			t.Errorf("expected room_id=42, got %q", vars["room_id"])
		}
		if vars["user"] != "bob" {
			t.Errorf("expected user=bob, got %q", vars["user"])
		}
	})
}

func TestRtmpAuthService_extractPathFromTCURL(t *testing.T) {
	channels := map[string]models.Stream{}
	svc := newTestRtmpAuthService(channels)

	tests := []struct {
		name   string
		tcurl  string
		expect string
	}{
		{"valid URL", "rtmp://localhost:1935/user/alice", "/user/alice"},
		{"URL with port only", "rtmp://localhost:1935/app", "/app"},
		{"no path", "rtmp://localhost", ""},
		{"malformed URL returns input", "://invalid", "://invalid"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := svc.extractPathFromTCURL(tt.tcurl)
			if got != tt.expect {
				t.Errorf("extractPathFromTCURL(%q) = %q, want %q", tt.tcurl, got, tt.expect)
			}
		})
	}
}
