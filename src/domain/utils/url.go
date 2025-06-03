package utils

import "strings"

// JoinURL joins URL parts while preserving the protocol
func JoinURL(base string, parts ...string) string {
	// Remove trailing slash from base if present
	base = strings.TrimRight(base, "/")
	
	// Join all parts with forward slashes, removing any leading/trailing slashes
	joined := base
	for _, part := range parts {
		part = strings.Trim(part, "/")
		if part != "" {
			joined += "/" + part
		}
	}
	return joined
} 