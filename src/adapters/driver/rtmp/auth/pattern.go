package auth

import (
	"net/url"
	"regexp"
)

// TODO : move to domain
// patternToRegex converts a pattern with {var} to a regex and returns the regex and the variable names
func patternToRegex(pattern string) (string, []string) {
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

// extractVariables matches the tcurl against the regex and extracts named variables
func extractVariables(regexStr string, varNames []string, tcurl string) (map[string]string, bool) {
	regex, err := regexp.Compile(regexStr)
	if err != nil {
		return nil, false
	}
	match := regex.FindStringSubmatch(tcurl)
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
func extractPathFromTCURL(tcurl string) string {
	parsedURL, err := url.Parse(tcurl)
	if err != nil {
		return tcurl
	}
	return parsedURL.Path
} 