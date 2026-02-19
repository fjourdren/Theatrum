#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../src"

echo "Running tests..."
go test ./...

echo "Building..."
CGO_ENABLED=0 go build -o ../theatrum ./cmd/main.go

echo "Done — binary at ./theatrum"
