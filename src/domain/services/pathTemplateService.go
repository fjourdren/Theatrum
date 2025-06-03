package services

import (
	"Theatrum/constants"
	"errors"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"
)

type PathTemplateService struct {
	allowedChars *regexp.Regexp
	noConsecutiveDots *regexp.Regexp
}

func NewPathTemplateService() *PathTemplateService {
	// Only allow alphanumeric characters, underscores, hyphens, and single dots (no consecutive dots)
	allowedChars := regexp.MustCompile(`^[a-zA-Z0-9_\-\.]+$`)
	noConsecutiveDots := regexp.MustCompile(`\.\.`) // TODO : improve + security test
	return &PathTemplateService{
		allowedChars: allowedChars,
		noConsecutiveDots: noConsecutiveDots,
	}
}

func (s *PathTemplateService) ExtractValues(template string, input string) (map[string]string, error) {
	// Normalize paths to use forward slashes
	template = filepath.ToSlash(template)
	input = filepath.ToSlash(input)

	// Find all {var} placeholders
	varRegex := regexp.MustCompile(constants.PlaceholderRegex)
	varNames := varRegex.FindAllStringSubmatch(template, -1)

	// Build regex pattern from template
	pattern := regexp.QuoteMeta(template)
	for _, match := range varNames {
		placeholder := regexp.QuoteMeta(constants.PlaceholderBegin + match[1] + constants.PlaceholderEnd)
		pattern = strings.Replace(pattern, placeholder, `([^/]+)`, 1)
	}

	// Compile regex
	re, err := regexp.Compile("^" + pattern)
	if err != nil {
		return nil, fmt.Errorf("failed to compile regex: %w", err)
	}

	// Match input path
	matches := re.FindStringSubmatch(input)
	if matches == nil || len(matches)-1 != len(varNames) {
		return nil, fmt.Errorf("input does not match template")
	}

	// Map variable names to matched values
	result := make(map[string]string)
	for i, match := range varNames {
		result[match[1]] = matches[i+1]
	}

	// Extract filename from input path if it exists
	if strings.Contains(input, "/") {
		filename := filepath.Base(input)
		if filename != "" && filename != "." && filename != "/" {
			result["FILENAME"] = filename
		}
	}

	return result, nil
}

func (s *PathTemplateService) ReplacePlaceholders(text string, vars map[string]string) (string, error) {
	// Regex to find {placeholder}
	re := regexp.MustCompile(constants.PlaceholderRegex)

	var err error
	result := re.ReplaceAllStringFunc(text, func(match string) string {
		key := strings.Trim(match, constants.PlaceholderBegin+constants.PlaceholderEnd)
		if val, ok := vars[key]; ok {
			sanitized, sanitizeErr := s.sanitizeValue(val)
			if sanitizeErr != nil {
				err = sanitizeErr
				return match // Leave placeholder as-is if error
			}
			return sanitized
		}
		return match // Leave placeholder if no matching variable
	})

	if err != nil {
		return "", err
	}

	// Use ToSlash to ensure forward slashes are used, then clean the path
	return filepath.ToSlash(filepath.Clean(result)), nil
}

func (s *PathTemplateService) sanitizeValue(value string) (string, error) {
	if !s.allowedChars.MatchString(value) {
		return "", errors.New("invalid characters in value: only a-z, A-Z, 0-9, _, - and . are allowed")
	}
	if s.noConsecutiveDots.MatchString(value) {
		return "", errors.New("consecutive dots are not allowed")
	}
	return value, nil
}
