package fileAccess

import (
	"Theatrum/constants"
	"Theatrum/domain/repositories"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// FileAccess implements the StoragePort interface for file system operations
type FileAccess struct{}

// Verify interface implementation
var _ repositories.StoragePort = (*FileAccess)(nil)

// NewFileAccess creates a new instance of FileAccess
func NewFileAccess() repositories.StoragePort {
	return &FileAccess{}
}

func (f *FileAccess) ReadFile(path string) ([]byte, error) {
	return os.ReadFile(path)
}

func (f *FileAccess) WriteFile(path string, data []byte) error {
	return os.WriteFile(path, data, 0644)
}

func (f *FileAccess) DeleteFile(path string) error {
	return os.Remove(path)
}

func (f *FileAccess) ListFiles(path string) ([]string, error) {
	return filepath.Glob(path)
}

func (f *FileAccess) GetFileSize(path string) (int64, error) {
	info, err := os.Stat(path)
	if err != nil {
		return 0, err
	}
	return info.Size(), nil
}

// SearchFiles walks the filesystem, finds every file that sits beneath
// a directory path that matches `pattern`, and returns
//   1. the full paths of those files, and
//   2. a slice of maps whose keys are the placeholder names that appeared
//      in the pattern (e.g. {username}) and whose values are the concrete
//      segments extracted from each matching path.
//
// Pattern rules:
//   * Placeholders are written {like_this} and must span an entire path
//     segment (no slashes inside).
//   * Everything else is treated literally.
//   * Example pattern: /data/{username}/{stream_name}/videos
//   * Pattern must start with a forward slash
//   * Pattern must not contain empty segments
//   * Pattern must not contain path traversal attempts (../, ..\, etc.)
//
// Returned slices preserve order of discovery (depth-first by default).
//
// Example:
//   pattern := "/data/{username}/{stream_name}/videos"
//   files, vars, err := fileAccess.SearchFiles(pattern)
//   
//   For files like:
//   	/data/john/stream1/videos/video1.mp4
//  	/data/alice/stream2/videos/video2.mp4
//   
//   files would be:
//   	[]string{
//     		"/data/john/stream1/videos/video1.mp4",
//     		"/data/alice/stream2/videos/video2.mp4",
//  	 }
//   
//   vars would be:
//   	[]map[string]string{
//     		{
//         		"username": "john",
//         		"stream_name": "stream1",
//     		},
//     		{
//         		"username": "alice",
//         		"stream_name": "stream2",
//     		},
//   	}
//
// Example usage:
/*
Fast test:
package main

import (
	"fmt"
	"log"

	fileAccess "Theatrum/adapters/driven/fileAccess/repositories"
)

func main() {
	fa := &fileAccess.FileAccess{}

	pattern := "data/{username}/{qualities}"
	//pattern := "data/{username}/default"

	files, vars, err := fa.SearchFiles(pattern, []string{".mp4"})
	if err != nil {
		log.Fatalf("search failed: %v", err)
	}

	for i, path := range files {
		fmt.Printf("%s → %v\n", path, vars[i])
	}
}
*/
// TODO : make it able to manage filename directly in the pattern
func (fa *FileAccess) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
	// Validate pattern to prevent path traversal
	if err := validatePattern(pattern); err != nil {
		return nil, nil, err
	}

	// Compute the max depth of the pattern (number of segments)
	maxDepth := strings.Count(pattern, "/")

	// 1. Build a regular expression from the pattern
	varNames := make([]string, 0) // Dynamic allocation for variable names
	var reBuilder strings.Builder
	reBuilder.WriteString("^") // anchor at the start

	// We'll also work out the longest literal prefix so we know where to
	// start the directory walk (huge speed-up on big trees).
	var walkRoot strings.Builder

	varAlreadyFound := false // This makes us able to manage a static dir name like "default" after a placeholder.
	hasFilename := false     // Track if pattern ends with a filename

	for i := 0; i < len(pattern); i++ {
		ch := pattern[i]
		switch {
		case ch == constants.PlaceholderBegin[0]:
			varAlreadyFound = true
			// finish any literal run *before* this brace
			reBuilder.WriteString("([^/]+)")
			// collect the var name
			j := strings.IndexByte(pattern[i+1:], constants.PlaceholderEnd[0])
			if j == -1 {
				return nil, nil, fmt.Errorf("unclosed placeholder in pattern %q", pattern)
			}
			name := pattern[i+1 : i+1+j]
			if name == "" {
				return nil, nil, fmt.Errorf("empty placeholder in pattern %q", pattern)
			}
			varNames = append(varNames, name)
			i += j // skip over the variable text; the loop's i++ will land on the closing brace
		case ch == constants.PlaceholderEnd[0]:
			// Nothing extra; the regex group has already been written, we just need to skip over the closing brace
		default:
			// ordinary byte
			reBuilder.WriteString(regexp.QuoteMeta(string(ch)))
			
			if !varAlreadyFound {
				walkRoot.WriteByte(ch) // only literals belong in the root
			}

			// Check if this is the last character and it's not a slash
			if i == len(pattern)-1 && ch != '/' {
				hasFilename = true
			}
		}
	}

	// Only append "/.+" if the pattern doesn't end with a filename
	if !hasFilename {
		reBuilder.WriteString("/.+$") // we expect *files* to sit beneath the dir
	} else {
		reBuilder.WriteString("$") // just anchor at the end for exact filename match
	}

	re, err := regexp.Compile(reBuilder.String())
	if err != nil {
		return nil, nil, fmt.Errorf("building regexp from pattern: %w", err)
	}

	// 2. Work out where to start walking
	root := filepath.Clean(walkRoot.String())
	if root == "" || root == string(filepath.Separator) {
		root = string(filepath.Separator)
	}

	// 3. Walk and match
	var (
		paths []string
		vars  []map[string]string
	)

	err = filepath.WalkDir(root, func(path string, d fs.DirEntry, walkErr error) error {
		path = strings.ReplaceAll(path, "\\", "/")

		// Calculate current depth by counting path separators
		currentDepth := strings.Count(path, "/")

		if walkErr != nil {
			return walkErr // propagate
		}

		// If we're at a directory and have exceeded max depth, skip it
		if d.IsDir() {
			if currentDepth > maxDepth {
				return filepath.SkipDir // Skip this directory and its contents
			}
			return nil // Continue walking
		}

		log.Printf("Walking %s (depth: %d)", path, currentDepth)

		// For files, check if they match our pattern
		// Use forward slashes for regex matching on every platform
		slashPath := filepath.ToSlash(path)
		if !re.MatchString(slashPath) {
			return nil // not of interest
		}

		// Check filename if specified in pattern
		if hasFilename {
			pathFilename := filepath.Base(slashPath)
			patternFilename := filepath.Base(pattern)
			if pathFilename != patternFilename {
				return nil // filename doesn't match
			}
		}

		// Check extensions if specified
		if len(extensions) > 0 {
			// Convert both the path extension and valid extensions to lowercase for case-insensitive comparison
			pathExt := strings.ToLower(filepath.Ext(path))
			validExt := false
			for _, ext := range extensions {
				if strings.ToLower(ext) == pathExt {
					validExt = true
					break
				}
			}
			if !validExt {
				return nil // extension doesn't match
			}
		}

		matches := re.FindStringSubmatch(slashPath)
		if len(matches) != len(varNames)+1 {
			// Should never happen, but be defensive
			return nil
		}

		m := make(map[string]string, len(varNames))
		for i, name := range varNames {
			m[name] = matches[i+1]
		}

		// Extract filename from the path if it exists
		if strings.Contains(path, "/") {
			filename := filepath.Base(path)
			if filename != "" && filename != "." && filename != "/" {
				m["FILENAME"] = filename
			}
		}

		paths = append(paths, path)
		vars = append(vars, m)
		return nil
	})

	if err != nil {
		fmt.Printf("Error searching files: %s\n", err)
		return nil, nil, err
	}

	return paths, vars, nil
}

// TODO : centralize security checks
// validatePattern performs security checks on the pattern to prevent path traversal attacks
func validatePattern(pattern string) error {
	// Check for path traversal attempts
	if strings.Contains(pattern, "..") {
		return fmt.Errorf("pattern cannot contain '..' (path traversal attempt)")
	}

	// Check for backslash path traversal (Windows-style)
	if strings.Contains(pattern, "\\") {
		return fmt.Errorf("pattern cannot contain backslashes (use forward slashes)")
	}

	// Check for empty segments
	segments := strings.Split(pattern, "/")
	for _, seg := range segments {
		if seg == "" && seg != segments[0] { // Allow empty first segment for leading slash
			return fmt.Errorf("pattern cannot contain empty segments")
		}
	}

	// TODO : centralize security checks
	// Check for any other potentially dangerous characters
	dangerousChars := []string{"%00", "%2e", "%2f", "%5c", "~", "|", ">", "<", "*", "?"}
	for _, char := range dangerousChars {
		if strings.Contains(pattern, char) {
			return fmt.Errorf("pattern contains potentially dangerous character: %s", char)
		}
	}

	// Validate placeholder syntax
	placeholderRegex := regexp.MustCompile(constants.PlaceholderRegex)
	allPlaceholders := placeholderRegex.FindAllString(pattern, -1)
	
	// Check that all placeholders are properly formed
	for _, ph := range allPlaceholders {
		if !strings.HasPrefix(ph, constants.PlaceholderBegin) || !strings.HasSuffix(ph, constants.PlaceholderEnd) {
			return fmt.Errorf("invalid placeholder syntax: %s", ph)
		}
	}

	// Check for nested placeholders
	if strings.Contains(pattern, constants.PlaceholderBegin+constants.PlaceholderBegin) || strings.Contains(pattern, constants.PlaceholderEnd+constants.PlaceholderEnd) {
		return fmt.Errorf("nested placeholders are not allowed")
	}

	return nil
}
