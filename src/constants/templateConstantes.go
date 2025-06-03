package constants

import (
	"regexp"
)

var (
	PlaceholderBegin = "{"
	PlaceholderEnd   = "}"
	PlaceholderRegex = regexp.QuoteMeta(PlaceholderBegin) + `[^` + regexp.QuoteMeta(PlaceholderBegin) + regexp.QuoteMeta(PlaceholderEnd) + `]+` + regexp.QuoteMeta(PlaceholderEnd)
)