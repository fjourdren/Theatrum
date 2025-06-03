package constants

import (
	"os"
	"strings"
)

var (
	workDir, _        = os.Getwd()
	workDirNormalized = strings.ReplaceAll(workDir, "\\", "/") // Convert backslashes to forward slashes in workDir
)