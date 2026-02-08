package services

import (
	"fmt"
	"testing"
)

func TestPathTemplateService_MatchesTemplate(t *testing.T) {
	service := NewPathTemplateService()

	tests := []struct {
		name     string
		template string
		input    string
		expected bool
	}{
		{
			name:     "exact match with single placeholder",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/alice/playlist.m3u8",
			expected: true,
		},
		{
			name:     "exact match with single placeholder but no match",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/alice/playlist.m3u8/extra",
			expected: false,
		},
		{
			name:     "exact match with multiple placeholders",
			template: "streams/{streamName}/quality/{quality}/segment.ts",
			input:    "streams/alice/quality/high/segment.ts",
			expected: true,
		},
		{
			name:     "no placeholders - exact match",
			template: "streams/static/playlist.m3u8",
			input:    "streams/static/playlist.m3u8",
			expected: true,
		},
		{
			name:     "no placeholders - no match",
			template: "streams/static/playlist.m3u8",
			input:    "streams/other/playlist.m3u8",
			expected: false,
		},
		{
			name:     "placeholder value with underscores",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/stream_name_123/playlist.m3u8",
			expected: true,
		},
		{
			name:     "placeholder value with hyphens",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/stream-name-123/playlist.m3u8",
			expected: true,
		},
		{
			name:     "placeholder value with dots",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/stream.name.123/playlist.m3u8",
			expected: true,
		},
		{
			name:     "mismatched path structure",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "videos/alice/playlist.m3u8",
			expected: false,
		},
		{
			name:     "extra path segments in input",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/alice/extra/playlist.m3u8",
			expected: false,
		},
		{
			name:     "missing path segments in input",
			template: "streams/{streamName}/quality/{quality}/playlist.m3u8",
			input:    "streams/alice/playlist.m3u8",
			expected: false,
		},
		{
			name:     "empty input",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "",
			expected: false,
		},
		{
			name:     "empty template",
			template: "",
			input:    "streams/alice/playlist.m3u8",
			expected: false,
		},
		{
			name:     "both empty",
			template: "",
			input:    "",
			expected: true,
		},
		{
			name:     "windows path separators in template",
			template: "streams\\{streamName}\\playlist.m3u8",
			input:    "streams/alice/playlist.m3u8",
			expected: true,
		},
		{
			name:     "windows path separators in input",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams\\alice\\playlist.m3u8",
			expected: true,
		},
		{
			name:     "placeholder with special regex characters in surrounding text",
			template: "streams.{streamName}+playlist.m3u8",
			input:    "streams.alice+playlist.m3u8",
			expected: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := service.MatchesTemplate(tt.template, tt.input)
			if result != tt.expected {
				t.Errorf("MatchesTemplate(%q, %q) = %v, expected %v", tt.template, tt.input, result, tt.expected)
			}
		})
	}
}

func TestPathTemplateService_ExtractValues(t *testing.T) {
	service := NewPathTemplateService()

	tests := []struct {
		name          string
		template      string
		input         string
		expectedVars  map[string]string
		expectedError bool
	}{
		{
			name:     "single placeholder extraction",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/alice/playlist.m3u8",
			expectedVars: map[string]string{
				"streamName": "alice",
				"FILENAME":   "playlist.m3u8",
			},
			expectedError: false,
		},
		{
			name:     "multiple placeholders extraction",
			template: "streams/{streamName}/quality/{quality}/segment.ts",
			input:    "streams/alice/quality/high/segment.ts",
			expectedVars: map[string]string{
				"streamName": "alice",
				"quality":    "high",
				"FILENAME":   "segment.ts",
			},
			expectedError: false,
		},
		{
			name:     "no placeholders",
			template: "streams/static/playlist.m3u8",
			input:    "streams/static/playlist.m3u8",
			expectedVars: map[string]string{
				"FILENAME": "playlist.m3u8",
			},
			expectedError: false,
		},
		{
			name:     "placeholder with complex values",
			template: "streams/{streamName}/playlist.m3u8",
			input:    "streams/stream_name-123.test/playlist.m3u8",
			expectedVars: map[string]string{
				"streamName": "stream_name-123.test",
				"FILENAME":   "playlist.m3u8",
			},
			expectedError: false,
		},
		{
			name:          "mismatched template",
			template:      "streams/{streamName}/playlist.m3u8",
			input:         "videos/alice/playlist.m3u8",
			expectedVars:  nil,
			expectedError: true,
		},
		{
			name:     "no filename in path",
			template: "streams/{streamName}",
			input:    "streams/alice",
			expectedVars: map[string]string{
				"streamName": "alice",
				"FILENAME":   "alice",
			},
			expectedError: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := service.ExtractValues(tt.template, tt.input)

			if (err != nil) != tt.expectedError {
				t.Errorf("ExtractValues(%q, %q) error = %v, expectedError %v", tt.template, tt.input, err, tt.expectedError)
				return
			}

			if tt.expectedError {
				return
			}

			if len(result) != len(tt.expectedVars) {
				t.Errorf("ExtractValues(%q, %q) returned %d variables, expected %d", tt.template, tt.input, len(result), len(tt.expectedVars))
				return
			}

			for key, expectedValue := range tt.expectedVars {
				if actualValue, exists := result[key]; !exists {
					t.Errorf("ExtractValues(%q, %q) missing key %q", tt.template, tt.input, key)
				} else if actualValue != expectedValue {
					t.Errorf("ExtractValues(%q, %q) key %q = %q, expected %q", tt.template, tt.input, key, actualValue, expectedValue)
				}
			}
		})
	}
}

func TestPathTemplateService_ReplacePlaceholders(t *testing.T) {
	service := NewPathTemplateService()

	tests := []struct {
		name          string
		template      string
		vars          map[string]string
		expected      string
		expectedError bool
	}{
		{
			name:     "single placeholder replacement",
			template: "streams/{streamName}/playlist.m3u8",
			vars: map[string]string{
				"streamName": "alice",
			},
			expected:      "streams/alice/playlist.m3u8",
			expectedError: false,
		},
		{
			name:     "multiple placeholders replacement",
			template: "streams/{streamName}/quality/{quality}/segment.ts",
			vars: map[string]string{
				"streamName": "alice",
				"quality":    "high",
			},
			expected:      "streams/alice/quality/high/segment.ts",
			expectedError: false,
		},
		{
			name:     "no placeholders",
			template: "streams/static/playlist.m3u8",
			vars:     map[string]string{},
			expected: "streams/static/playlist.m3u8",
			expectedError: false,
		},
		{
			name:     "missing variable for placeholder",
			template: "streams/{streamName}/playlist.m3u8",
			vars:     map[string]string{},
			expected: "streams/{streamName}/playlist.m3u8",
			expectedError: false,
		},
		{
			name:     "extra variables",
			template: "streams/{streamName}/playlist.m3u8",
			vars: map[string]string{
				"streamName": "alice",
				"extra":      "value",
			},
			expected:      "streams/alice/playlist.m3u8",
			expectedError: false,
		},
		{
			name:     "valid characters in value",
			template: "streams/{streamName}/playlist.m3u8",
			vars: map[string]string{
				"streamName": "alice_123-test.stream",
			},
			expected:      "streams/alice_123-test.stream/playlist.m3u8",
			expectedError: false,
		},
		{
			name:     "invalid characters in value",
			template: "streams/{streamName}/playlist.m3u8",
			vars: map[string]string{
				"streamName": "alice@test",
			},
			expected:      "",
			expectedError: true,
		},
		{
			name:     "consecutive dots in value",
			template: "streams/{streamName}/playlist.m3u8",
			vars: map[string]string{
				"streamName": "alice..test",
			},
			expected:      "",
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := service.ReplacePlaceholders(tt.template, tt.vars)

			if (err != nil) != tt.expectedError {
				t.Errorf("ReplacePlaceholders(%q, %v) error = %v, expectedError %v", tt.template, tt.vars, err, tt.expectedError)
				return
			}

			if !tt.expectedError && result != tt.expected {
				t.Errorf("ReplacePlaceholders(%q, %v) = %q, expected %q", tt.template, tt.vars, result, tt.expected)
			}
		})
	}
}

func TestPathTemplateService_ReplacePlaceholders_BuiltinFunctions(t *testing.T) {
	t.Run("UUID replacement via vars", func(t *testing.T) {
		service := NewPathTemplateService()

		result, err := service.ReplacePlaceholders("streams/{%UUID%}/playlist.m3u8", map[string]string{
			"UUID": "550e8400-e29b-41d4-a716-446655440000",
		})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		expected := "streams/550e8400-e29b-41d4-a716-446655440000/playlist.m3u8"
		if result != expected {
			t.Errorf("got %q, expected %q", result, expected)
		}
	})

	t.Run("STARTING_DATE replacement via vars", func(t *testing.T) {
		service := NewPathTemplateService()

		result, err := service.ReplacePlaceholders("livestreams/{%STARTING_DATE%}/output", map[string]string{
			"STARTING_DATE": "2026-02-07_15-30-00",
		})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		expected := "livestreams/2026-02-07_15-30-00/output"
		if result != expected {
			t.Errorf("got %q, expected %q", result, expected)
		}
	})

	t.Run("mixed builtin and user variables", func(t *testing.T) {
		service := NewPathTemplateService()

		result, err := service.ReplacePlaceholders("livestreams/{username}/{%UUID%}/playlist.m3u8", map[string]string{
			"username": "alice",
			"UUID":     "abcd-1234",
		})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		expected := "livestreams/alice/abcd-1234/playlist.m3u8"
		if result != expected {
			t.Errorf("got %q, expected %q", result, expected)
		}
	})

	t.Run("unknown builtin function returns error", func(t *testing.T) {
		service := NewPathTemplateService()

		_, err := service.ReplacePlaceholders("streams/{%UNKNOWN%}/playlist.m3u8", map[string]string{})
		if err == nil {
			t.Fatal("expected error for unknown builtin function, got nil")
		}
		if err.Error() != "unknown builtin function: UNKNOWN" {
			t.Errorf("unexpected error message: %v", err)
		}
	})

	t.Run("no conflict between builtin and user variable syntax", func(t *testing.T) {
		service := NewPathTemplateService()

		result, err := service.ReplacePlaceholders("{name}/{%UUID%}", map[string]string{
			"name": "bob",
			"UUID": "abcd-1234",
		})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		expected := "bob/abcd-1234"
		if result != expected {
			t.Errorf("got %q, expected %q", result, expected)
		}
	})

	t.Run("known builtin not in vars is left as-is", func(t *testing.T) {
		service := NewPathTemplateService()

		result, err := service.ReplacePlaceholders("livestreams/{username}/{%UUID%}", map[string]string{
			"username": "alice",
		})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		// UUID not in vars → left as literal for stream key computation
		expected := "livestreams/alice/{%UUID%}"
		if result != expected {
			t.Errorf("got %q, expected %q", result, expected)
		}
	})

	t.Run("multiple occurrences of same builtin use same value from vars", func(t *testing.T) {
		service := NewPathTemplateService()

		result, err := service.ReplacePlaceholders("{%UUID%}/{%UUID%}", map[string]string{
			"UUID": "same-uuid",
		})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		expected := "same-uuid/same-uuid"
		if result != expected {
			t.Errorf("got %q, expected %q", result, expected)
		}
	})
}

func TestPathTemplateService_GenerateBuiltinVars(t *testing.T) {
	t.Run("generates UUID when template contains UUID builtin", func(t *testing.T) {
		service := NewPathTemplateService()
		service.RegisterBuiltinFunc("UUID", func() string {
			return "test-uuid-value"
		})

		vars := service.GenerateBuiltinVars("streams/{%UUID%}/output")
		if val, ok := vars["UUID"]; !ok {
			t.Fatal("expected UUID in generated vars")
		} else if val != "test-uuid-value" {
			t.Errorf("got %q, expected %q", val, "test-uuid-value")
		}
	})

	t.Run("generates STARTING_DATE when template contains it", func(t *testing.T) {
		service := NewPathTemplateService()
		service.RegisterBuiltinFunc("STARTING_DATE", func() string {
			return "2026-02-07_15-30-00"
		})

		vars := service.GenerateBuiltinVars("livestreams/{%STARTING_DATE%}/output")
		if val, ok := vars["STARTING_DATE"]; !ok {
			t.Fatal("expected STARTING_DATE in generated vars")
		} else if val != "2026-02-07_15-30-00" {
			t.Errorf("got %q, expected %q", val, "2026-02-07_15-30-00")
		}
	})

	t.Run("returns empty map when no builtins in template", func(t *testing.T) {
		service := NewPathTemplateService()

		vars := service.GenerateBuiltinVars("streams/{username}/output")
		if len(vars) != 0 {
			t.Errorf("expected empty map, got %v", vars)
		}
	})

	t.Run("generates each builtin only once even if repeated", func(t *testing.T) {
		service := NewPathTemplateService()
		callCount := 0
		service.RegisterBuiltinFunc("UUID", func() string {
			callCount++
			return "uuid-value"
		})

		vars := service.GenerateBuiltinVars("{%UUID%}/{%UUID%}")
		if callCount != 1 {
			t.Errorf("expected generator called once, got %d", callCount)
		}
		if vars["UUID"] != "uuid-value" {
			t.Errorf("got %q, expected %q", vars["UUID"], "uuid-value")
		}
	})

	t.Run("ignores unknown builtins", func(t *testing.T) {
		service := NewPathTemplateService()

		vars := service.GenerateBuiltinVars("streams/{%UNKNOWN%}/output")
		if _, ok := vars["UNKNOWN"]; ok {
			t.Fatal("did not expect UNKNOWN in generated vars")
		}
	})
}

func TestLiveStreamRegistry(t *testing.T) {
	t.Run("GetOrRegister stores and returns new vars", func(t *testing.T) {
		registry := NewLiveStreamRegistry()
		vars := map[string]string{"UUID": "abc-123"}

		result := registry.GetOrRegister("key1", vars)
		if result["UUID"] != "abc-123" {
			t.Errorf("got %q, expected %q", result["UUID"], "abc-123")
		}
	})

	t.Run("GetOrRegister returns existing vars on reconnection", func(t *testing.T) {
		registry := NewLiveStreamRegistry()
		first := map[string]string{"UUID": "first-uuid"}
		second := map[string]string{"UUID": "second-uuid"}

		registry.GetOrRegister("key1", first)
		result := registry.GetOrRegister("key1", second)

		if result["UUID"] != "first-uuid" {
			t.Errorf("got %q, expected %q (should reuse first registration)", result["UUID"], "first-uuid")
		}
	})

	t.Run("GetBuiltinVars returns stored vars", func(t *testing.T) {
		registry := NewLiveStreamRegistry()
		registry.GetOrRegister("key1", map[string]string{"UUID": "abc-123"})

		vars, ok := registry.GetBuiltinVars("key1")
		if !ok {
			t.Fatal("expected to find vars for key1")
		}
		if vars["UUID"] != "abc-123" {
			t.Errorf("got %q, expected %q", vars["UUID"], "abc-123")
		}
	})

	t.Run("GetBuiltinVars returns false for unknown key", func(t *testing.T) {
		registry := NewLiveStreamRegistry()

		_, ok := registry.GetBuiltinVars("unknown")
		if ok {
			t.Fatal("expected false for unknown key")
		}
	})

	t.Run("Unregister removes entry", func(t *testing.T) {
		registry := NewLiveStreamRegistry()
		registry.GetOrRegister("key1", map[string]string{"UUID": "abc-123"})

		registry.Unregister("key1")

		_, ok := registry.GetBuiltinVars("key1")
		if ok {
			t.Fatal("expected false after unregister")
		}
	})

	t.Run("concurrent GetOrRegister returns consistent values", func(t *testing.T) {
		registry := NewLiveStreamRegistry()
		results := make(chan string, 100)

		for i := 0; i < 100; i++ {
			go func(id int) {
				vars := map[string]string{"UUID": fmt.Sprintf("uuid-%d", id)}
				result := registry.GetOrRegister("key1", vars)
				results <- result["UUID"]
			}(i)
		}

		// All goroutines should get the same value (whichever registered first)
		var firstResult string
		for i := 0; i < 100; i++ {
			r := <-results
			if firstResult == "" {
				firstResult = r
			} else if r != firstResult {
				t.Errorf("inconsistent result: got %q and %q", firstResult, r)
			}
		}
	})
}

func TestPathTemplateService_sanitizeValue(t *testing.T) {
	service := NewPathTemplateService()

	tests := []struct {
		name          string
		value         string
		expected      string
		expectedError bool
	}{
		{
			name:          "valid alphanumeric",
			value:         "alice123",
			expected:      "alice123",
			expectedError: false,
		},
		{
			name:          "valid with underscores",
			value:         "alice_test_123",
			expected:      "alice_test_123",
			expectedError: false,
		},
		{
			name:          "valid with hyphens",
			value:         "alice-test-123",
			expected:      "alice-test-123",
			expectedError: false,
		},
		{
			name:          "valid with single dots",
			value:         "alice.test.123",
			expected:      "alice.test.123",
			expectedError: false,
		},
		{
			name:          "invalid with special characters",
			value:         "alice@test",
			expected:      "",
			expectedError: true,
		},
		{
			name:          "invalid with consecutive dots",
			value:         "alice..test",
			expected:      "",
			expectedError: true,
		},
		{
			name:          "invalid with spaces",
			value:         "alice test",
			expected:      "",
			expectedError: true,
		},
		{
			name:          "empty value",
			value:         "",
			expected:      "",
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := service.sanitizeValue(tt.value)

			if (err != nil) != tt.expectedError {
				t.Errorf("sanitizeValue(%q) error = %v, expectedError %v", tt.value, err, tt.expectedError)
				return
			}

			if !tt.expectedError && result != tt.expected {
				t.Errorf("sanitizeValue(%q) = %q, expected %q", tt.value, result, tt.expected)
			}
		})
	}
} 