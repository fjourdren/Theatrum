package auth

import (
	"fmt"
)

// TODO : move to domain
// Authorizer handles URL pattern matching and authorization
type Authorizer struct {
	authorizedPatterns []string
}

// NewAuthorizer creates a new authorizer with the given patterns
func NewAuthorizer(patterns []string) *Authorizer {
	return &Authorizer{
		authorizedPatterns: patterns,
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

// TODO : remove debug prints
// ExtractVariables extracts variables from TCURL using the first matching pattern
func (a *Authorizer) ExtractVariables(tcurl string) (map[string]string, bool) {
	path := extractPathFromTCURL(tcurl)
	
	for _, pattern := range a.authorizedPatterns {
		regexStr, varNames := patternToRegex(pattern)
		fmt.Println("regexStr", regexStr)
		fmt.Println("varNames", varNames)
		fmt.Println("path", path)
		vars, ok := extractVariables(regexStr, varNames, path)
		fmt.Println("vars", vars)
		fmt.Println("ok", ok)
		if ok {
			return vars, true
		}
	}
	return nil, false
}

// ValidateAuthentication validates authentication rules based on extracted variables and publishingName
func (a *Authorizer) ValidateAuthentication(vars map[string]string, publishingName string) error {
	if publishingName == "" {
		return fmt.Errorf("empty publishingName provided")
	}

	// If the pattern contains a username variable, check it matches publishingName
	if username, exists := vars["username"]; exists {
		if username != publishingName {
			return fmt.Errorf("extracted username '%s' does not match publishingName '%s'", username, publishingName)
		}
	}

	// Add more authentication rules here as needed
	// For example, you could validate other variables like {host}, {app}, etc.
	
	return nil
}

 