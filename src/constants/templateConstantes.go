package constants

import (
	"regexp"
)

var (
	PlaceholderBegin = "{"
	PlaceholderEnd   = "}"
	PlaceholderRegex = regexp.QuoteMeta(PlaceholderBegin) + `([^` + regexp.QuoteMeta(PlaceholderBegin) + regexp.QuoteMeta(PlaceholderEnd) + `]+)` + regexp.QuoteMeta(PlaceholderEnd)

	BuiltinFuncBegin = "{%"
	BuiltinFuncEnd   = "%}"
	BuiltinFuncRegex = `\{%([A-Z_]+)%\}`

	BuiltinFuncStartingDate = "STARTING_DATE"
	BuiltinFuncUUID         = "UUID"

	StartingDateFormat = "2006-01-02_15-04-05"
)