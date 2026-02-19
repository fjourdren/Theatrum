package models

import "testing"

func TestConnectionInfo_GetVar(t *testing.T) {
	tests := []struct {
		name      string
		vars      map[string]string
		key       string
		wantVal   string
		wantFound bool
	}{
		{"found", map[string]string{"username": "alice"}, "username", "alice", true},
		{"not found", map[string]string{"username": "alice"}, "room_id", "", false},
		{"nil vars", nil, "username", "", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := &ConnectionInfo{Vars: tt.vars}
			val, found := c.GetVar(tt.key)
			if val != tt.wantVal {
				t.Errorf("GetVar(%q) value = %q, want %q", tt.key, val, tt.wantVal)
			}
			if found != tt.wantFound {
				t.Errorf("GetVar(%q) found = %v, want %v", tt.key, found, tt.wantFound)
			}
		})
	}
}

func TestConnectionInfo_GetVars(t *testing.T) {
	t.Run("returns copy", func(t *testing.T) {
		c := &ConnectionInfo{Vars: map[string]string{"a": "1", "b": "2"}}
		vars := c.GetVars()
		if len(vars) != 2 {
			t.Errorf("expected 2 vars, got %d", len(vars))
		}
		// Modify copy, original should be unaffected
		vars["c"] = "3"
		if len(c.Vars) != 2 {
			t.Error("original vars should not be modified")
		}
	})

	t.Run("nil vars returns empty map", func(t *testing.T) {
		c := &ConnectionInfo{}
		vars := c.GetVars()
		if vars == nil {
			t.Error("expected non-nil map")
		}
		if len(vars) != 0 {
			t.Errorf("expected empty map, got %d entries", len(vars))
		}
	})
}

func TestConnectionInfo_GetUsername(t *testing.T) {
	t.Run("found", func(t *testing.T) {
		c := &ConnectionInfo{Vars: map[string]string{"username": "alice"}}
		val, ok := c.GetUsername()
		if !ok || val != "alice" {
			t.Errorf("expected (alice, true), got (%q, %v)", val, ok)
		}
	})

	t.Run("not found", func(t *testing.T) {
		c := &ConnectionInfo{Vars: map[string]string{}}
		_, ok := c.GetUsername()
		if ok {
			t.Error("expected not found")
		}
	})
}

func TestConnectionInfo_GetHost(t *testing.T) {
	t.Run("found", func(t *testing.T) {
		c := &ConnectionInfo{Vars: map[string]string{"host": "example.com"}}
		val, ok := c.GetHost()
		if !ok || val != "example.com" {
			t.Errorf("expected (example.com, true), got (%q, %v)", val, ok)
		}
	})

	t.Run("not found", func(t *testing.T) {
		c := &ConnectionInfo{Vars: map[string]string{}}
		_, ok := c.GetHost()
		if ok {
			t.Error("expected not found")
		}
	})
}

func TestConnectionInfo_GetAppName(t *testing.T) {
	t.Run("found", func(t *testing.T) {
		c := &ConnectionInfo{Vars: map[string]string{"app": "myapp"}}
		val, ok := c.GetAppName()
		if !ok || val != "myapp" {
			t.Errorf("expected (myapp, true), got (%q, %v)", val, ok)
		}
	})

	t.Run("not found", func(t *testing.T) {
		c := &ConnectionInfo{Vars: map[string]string{}}
		_, ok := c.GetAppName()
		if ok {
			t.Error("expected not found")
		}
	})
}
