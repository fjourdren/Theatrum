package services

import (
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