package services

import (
	"Theatrum/domain/models"
	"encoding/hex"
	"fmt"
	"net/url"
	"regexp"
)

// RtmpAuthService handles RTMP authentication and URL pattern matching
type RtmpAuthService struct {
	channels        *map[string]models.Stream
	templateService *PathTemplateService
}

// NewRtmpAuthService creates a new RTMP authentication service
func NewRtmpAuthService(channels *map[string]models.Stream, templateService *PathTemplateService) *RtmpAuthService {
	return &RtmpAuthService{
		channels:        channels,
		templateService: templateService,
	}
}

// IsAuthorized checks if the TCURL matches any authorized channel pattern
func (s *RtmpAuthService) IsAuthorized(tcurl string) bool {
	path := s.extractPathFromTCURL(tcurl)

	for pattern := range *s.channels {
		regexStr, varNames := s.patternToRegex(pattern)
		_, ok := s.extractVariables(regexStr, varNames, path)
		if ok {
			return true
		}
	}

	return false
}

// ExtractChannel extracts channel configuration and variables from TCURL
func (s *RtmpAuthService) ExtractChannel(tcurl string) (*models.Stream, map[string]string, bool) {
	path := s.extractPathFromTCURL(tcurl)

	for pattern, stream := range *s.channels {
		regexStr, varNames := s.patternToRegex(pattern)
		vars, ok := s.extractVariables(regexStr, varNames, path)
		if ok {
			return &stream, vars, true
		}
	}

	return nil, nil, false
}

// ValidateAuthentication validates the publishing token using XOR authentication
func (s *RtmpAuthService) ValidateAuthentication(stream *models.Stream, vars map[string]string, publishingName string) error {
	if publishingName == "" {
		return fmt.Errorf("empty publishingName provided")
	}

	// Basic authentication using username XORed with live_stream_key
	if username, exists := vars["username"]; exists {
		expectedToken := s.xorString(stream.LiveStreamKey, username)
		if publishingName != expectedToken {
			return fmt.Errorf("invalid authentication token")
		}
	}

	return nil
}

// BuildStreamPath builds the output path for a stream using template variables
func (s *RtmpAuthService) BuildStreamPath(stream *models.Stream, vars map[string]string) (string, error) {
	return s.templateService.ReplacePlaceholders(stream.Path, vars)
}

// xorString performs XOR operation between a string and the live stream key
func (s *RtmpAuthService) xorString(liveStreamKey string, input string) string {
	inputBytes := []byte(input)
	result := make([]byte, len(inputBytes))

	for i := 0; i < len(inputBytes); i++ {
		result[i] = inputBytes[i] ^ liveStreamKey[i%len(liveStreamKey)]
	}

	return hex.EncodeToString(result)
}

// patternToRegex converts a pattern with {var} placeholders to a regex
func (s *RtmpAuthService) patternToRegex(pattern string) (string, []string) {
	varNames := []string{}
	// First, escape the entire pattern to handle literal characters
	escapedPattern := regexp.QuoteMeta(pattern)
	// Then find and replace escaped variable placeholders with regex capture groups
	regex := regexp.MustCompile(`\\{([a-zA-Z0-9_]+)\\}`)
	regexPattern := regex.ReplaceAllStringFunc(escapedPattern, func(m string) string {
		// Extract variable name from \{varname\}
		name := m[2 : len(m)-2] // Remove \{ and \}
		varNames = append(varNames, name)
		return "(?P<" + name + ">[^/]+)"
	})
	return "^" + regexPattern + "$", varNames
}

// extractVariables matches the path against the regex and extracts named variables
func (s *RtmpAuthService) extractVariables(regexStr string, varNames []string, path string) (map[string]string, bool) {
	regex, err := regexp.Compile(regexStr)
	if err != nil {
		return nil, false
	}
	match := regex.FindStringSubmatch(path)
	if match == nil {
		return nil, false
	}
	result := map[string]string{}
	for i, name := range regex.SubexpNames() {
		if i != 0 && name != "" {
			result[name] = match[i]
		}
	}
	return result, true
}

// extractPathFromTCURL extracts the path component from a TCURL
func (s *RtmpAuthService) extractPathFromTCURL(tcurl string) string {
	parsedURL, err := url.Parse(tcurl)
	if err != nil {
		return tcurl
	}
	return parsedURL.Path
}
