package ports

import (
	"context"

	"github.com/gorilla/mux"
)

// HttpPort defines the interface for HTTP server operations
type HttpPort interface {
	// StartHttpServer starts the HTTP server and blocks until an error occurs
	StartHttpServer() error

	// Shutdown gracefully shuts down the HTTP server
	Shutdown(ctx context.Context) error

	// BuildRouter builds and returns the HTTP router with all routes configured
	BuildRouter() *mux.Router
}
