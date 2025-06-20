package auth

import (
	"encoding/hex"
	"fmt"
)

// TODO : move to domain
// Authorizer handles URL pattern matching and authorization
type Authorizer struct {
	authorizedPatterns []string
	secret             []byte
}

// NewAuthorizer creates a new authorizer with the given patterns
func NewAuthorizer(patterns []string, secret string) *Authorizer {
	return &Authorizer{
		authorizedPatterns: patterns,
		secret:            []byte(secret),
	}
}

// AuthorizedPatterns returns the current authorized patterns
func (a *Authorizer) AuthorizedPatterns() []string {
	return a.authorizedPatterns
}

// IsAuthorized checks if the TCURL matches any authorized pattern
func (a *Authorizer) IsAuthorized(tcurl string) bool {
	path := extractPathFromTCURL(tcurl)
	
	for _, pattern := range a.authorizedPatterns {
		regexStr, varNames := patternToRegex(pattern)
		_, ok := extractVariables(regexStr, varNames, path)
		if ok {
			return true
		}
	}
	return false
}

// ExtractVariables extracts variables from TCURL using the first matching pattern
func (a *Authorizer) ExtractVariables(tcurl string) (map[string]string, bool) {
	path := extractPathFromTCURL(tcurl)
	
	for _, pattern := range a.authorizedPatterns {
		regexStr, varNames := patternToRegex(pattern)
		vars, ok := extractVariables(regexStr, varNames, path)
		if ok {
			return vars, true
		}
	}
	return nil, false
}

// TODO : move this
// xorString performs XOR operation between a string and a byte slice
func (a *Authorizer) xorString(input string) string {
	inputBytes := []byte(input)
	result := make([]byte, len(inputBytes))
	
	for i := 0; i < len(inputBytes); i++ {
		result[i] = inputBytes[i] ^ a.secret[i%len(a.secret)]
	}
	
	return hex.EncodeToString(result)
}

// ValidateAuthentication validates authentication rules based on extracted variables and publishingName
func (a *Authorizer) ValidateAuthentication(vars map[string]string, publishingName string) error {
	if publishingName == "" {
		return fmt.Errorf("empty publishingName provided")
	}

	// Basic authentication using username XORed with live_stream_key
	if username, exists := vars["username"]; exists {
		expectedToken := a.xorString(username)
		if publishingName != expectedToken {
			return fmt.Errorf("invalid authentication token")
		}
	}
	
	return nil
}

 