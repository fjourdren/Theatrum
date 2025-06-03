# Start from the official Golang image
FROM golang:1.24.2 AS builder

# Set working directory
WORKDIR /app

# Copy go.mod and go.sum
COPY src/go.mod src/go.sum ./

# Download dependencies
RUN go mod download

# Copy the rest of the source code
COPY src/. .

# Build the application
RUN CGO_ENABLED=0 GOOS=linux go build -o main ./cmd/main.go



# ────────────────────────────────────────────────────────────────────────────────
# Use a minimal base image
FROM alpine:latest

# Install runtime dependencies
RUN apk add --no-cache ca-certificates tzdata ffmpeg

# Create non-root user
RUN adduser -D -g '' appuser

# Set working directory
WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/main .

# Copy config file
COPY config.yml .

# Copy hls directory
COPY hls ./hls

# Copy frontend directory
COPY frontend ./frontend

# Set proper permissions
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Open port 8080
EXPOSE 8080

# Run the binary
CMD ["./main"]
