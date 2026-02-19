package services

import (
	"Theatrum/constants"
	"errors"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
)

type BuiltinFunc func() string

type PathTemplateService struct {
	allowedChars      *regexp.Regexp
	noConsecutiveDots *regexp.Regexp
	builtinFuncs      map[string]BuiltinFunc
}

func NewPathTemplateService() *PathTemplateService {
	// Only allow alphanumeric characters, underscores, hyphens, and single dots (no consecutive dots)
	allowedChars := regexp.MustCompile(`^[a-zA-Z0-9_\-\.]+$`)
	noConsecutiveDots := regexp.MustCompile(`\.\.`) // TODO : improve + security test

	builtinFuncs := map[string]BuiltinFunc{
		constants.BuiltinFuncStartingDate: func() string {
			return time.Now().Format(constants.StartingDateFormat)
		},
		constants.BuiltinFuncUUID: func() string {
			return uuid.New().String()
		},
	}

	return &PathTemplateService{
		allowedChars:      allowedChars,
		noConsecutiveDots: noConsecutiveDots,
		builtinFuncs:      builtinFuncs,
	}
}

func (s *PathTemplateService) RegisterBuiltinFunc(name string, fn BuiltinFunc) {
	s.builtinFuncs[name] = fn
}

func (s *PathTemplateService) ExtractValues(template string, input string) (map[string]string, error) {
	// Normalize paths to use forward slashes (strings.ReplaceAll works cross-platform)
	template = strings.ReplaceAll(template, "\\", "/")
	input = strings.ReplaceAll(input, "\\", "/")

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

func (s *PathTemplateService) MatchesTemplate(template string, input string) bool {
	// Normalize paths to use forward slashes (strings.ReplaceAll works cross-platform)
	template = strings.ReplaceAll(template, "\\", "/")
	input = strings.ReplaceAll(input, "\\", "/")

	// Find all {var} placeholders
	varRegex := regexp.MustCompile(constants.PlaceholderRegex)
	varNames := varRegex.FindAllString(template, -1)

	// Build regex pattern from template
	pattern := regexp.QuoteMeta(template)
	for _, placeholder := range varNames {
		quotedPlaceholder := regexp.QuoteMeta(placeholder)
		pattern = strings.Replace(pattern, quotedPlaceholder, `([^/]+)`, 1)
	}

	// Compile regex
	re, err := regexp.Compile("^" + pattern + "$")
	if err != nil {
		return false
	}

	// Match input path
	matches := re.FindStringSubmatch(input)
	return matches != nil && len(matches)-1 == len(varNames)
}

func (s *PathTemplateService) ReplacePlaceholders(text string, vars map[string]string) (string, error) {
	// Phase 1: Replace builtin placeholders {%FUNC%} using values from vars map.
	// Builtins must be pre-generated via GenerateBuiltinVars() and passed in vars.
	// If a known builtin is not in vars, it is left as-is (useful for stream key computation).
	builtinRe := regexp.MustCompile(constants.BuiltinFuncRegex)

	var err error
	result := builtinRe.ReplaceAllStringFunc(text, func(match string) string {
		// Extract function name from {%NAME%}
		submatch := builtinRe.FindStringSubmatch(match)
		funcName := submatch[1]

		// Validate that the function is known
		if _, ok := s.builtinFuncs[funcName]; !ok {
			err = fmt.Errorf("unknown builtin function: %s", funcName)
			return match
		}

		// Look up pre-generated value from vars
		if val, ok := vars[funcName]; ok {
			sanitized, sanitizeErr := s.sanitizeValue(val)
			if sanitizeErr != nil {
				err = sanitizeErr
				return match
			}
			return sanitized
		}

		// Known builtin but not in vars — leave as-is
		return match
	})

	if err != nil {
		return "", err
	}

	// Phase 2: Replace user variables {var}
	placeholderRe := regexp.MustCompile(constants.PlaceholderRegex)

	result = placeholderRe.ReplaceAllStringFunc(result, func(match string) string {
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

	// Clean the path and ensure forward slashes
	result = filepath.Clean(result)
	return strings.ReplaceAll(result, "\\", "/"), nil
}

// GenerateBuiltinVars scans a template for {%FUNC%} patterns and calls each
// registered generator once, returning a map of function name -> generated value.
// Used at RTMP publish time to produce values that are then stored in the registry.
func (s *PathTemplateService) GenerateBuiltinVars(template string) map[string]string {
	builtinRe := regexp.MustCompile(constants.BuiltinFuncRegex)
	matches := builtinRe.FindAllStringSubmatch(template, -1)

	result := make(map[string]string)
	for _, match := range matches {
		funcName := match[1]
		if fn, ok := s.builtinFuncs[funcName]; ok {
			if _, alreadyGenerated := result[funcName]; !alreadyGenerated {
				result[funcName] = fn()
			}
		}
	}
	return result
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
