package repositories

// StoragePort defines the interface for file storage operations
type StoragePort interface {
	// ReadFile reads the contents of a file at the given path
	ReadFile(path string) ([]byte, error)

	// WriteFile writes data to a file at the given path
	WriteFile(path string, data []byte) error

	// DeleteFile removes a file at the given path
	DeleteFile(path string) error

	// ListFiles returns a list of files matching the given glob pattern
	ListFiles(pattern string) ([]string, error)

	// GetFileSize returns the size of a file in bytes
	GetFileSize(path string) (int64, error)

	// SearchFiles searches for files matching a pattern (file name or path), an optional list of extensions and returns both the file paths
	// and extracted variables from the pattern placeholders
	// Pattern rules:
	//   * Placeholders are written {like_this} and must span an entire path segment
	//   * Pattern must start with a forward slash
	//   * Pattern must not contain empty segments or path traversal attempts
	SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error)
}
