package auth

import (
	"Theatrum/adapters/driver/rtmp/models"
	domainModels "Theatrum/domain/models"
	"Theatrum/domain/services"
	"encoding/hex"
	"fmt"
	"log"
)

// LATER : move to domain
// Authorizer handles URL pattern matching and authorization
type Authorizer struct {
	applicationService *services.ApplicationService
}

// NewAuthorizer creates a new authorizer with the given patterns
func NewAuthorizer(applicationService *services.ApplicationService) *Authorizer {
	return &Authorizer{
		applicationService: applicationService,
	}
}

// IsAuthorized checks if the TCURL matches any authorized pattern
func (a *Authorizer) IsAuthorized(tcurl string) bool {
	path := extractPathFromTCURL(tcurl)
	
	for pattern, _ := range *a.applicationService.GetChannels() {
		regexStr, varNames := patternToRegex(pattern)
		_, ok := extractVariables(regexStr, varNames, path)
		if ok {
			return true
		}
	}

	return false
}

// Extract Channel from TCURL
func (a *Authorizer) ExtractChannel(tcurl string) (*domainModels.Stream, map[string]string, bool) {
	path := extractPathFromTCURL(tcurl)
	
	for pattern, stream := range *a.applicationService.GetChannels() {
		regexStr, varNames := patternToRegex(pattern)
		vars, ok := extractVariables(regexStr, varNames, path)
		log.Printf("vars: %v", vars)
		if ok {
			return &stream, vars, true
		}
	}

	return nil, nil, false
}

// TODO : move this
// xorString performs XOR operation between a string and a byte slice
func (a *Authorizer) xorString(liveStreamKey string, input string) string {
	inputBytes := []byte(input)
	result := make([]byte, len(inputBytes))
	
	for i := 0; i < len(inputBytes); i++ {
		result[i] = inputBytes[i] ^ liveStreamKey[i%len(liveStreamKey)]
	}
	
	return hex.EncodeToString(result)
}

// ValidateAuthentication validates authentication rules based on extracted variables and publishingName
func (a *Authorizer) ValidateAuthentication(connInfo *models.ConnectionInfo, publishingName string) error {
	if publishingName == "" {
		return fmt.Errorf("empty publishingName provided")
	}

	// Basic authentication using username XORed with live_stream_key
	if username, exists := connInfo.GetUsername(); exists {
		expectedToken := a.xorString(connInfo.Stream.LiveStreamKey, username)
		log.Printf("Expected token: %s, Publishing name: %s", expectedToken, publishingName)
		if publishingName != expectedToken {
			return fmt.Errorf("invalid authentication token")
		}
	}
	
	return nil
}

 