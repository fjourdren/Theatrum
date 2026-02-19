//go:build e2e

package e2e

import (
	"fmt"
	"net/http"
	"strings"
	"testing"
)

func TestMetrics_EndpointReturns200(t *testing.T) {
	ts := setupTestServer(t, TestConfig{
		Channels: `  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "test-key"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
`,
	})

	url := fmt.Sprintf("http://%s/metrics", ts.HTTPAddr)
	status, body := httpGetBody(t, url)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 from /metrics, got %d", status)
	}

	// Verify that some theatrum-prefixed metrics are present
	// Note: we use testable metrics with "test_" prefix in E2E tests,
	// but the /metrics endpoint exposes the default prometheus registry.
	// Since we use testable metrics (not registered globally), we just
	// check that the endpoint responds and has standard Go metrics.
	if !strings.Contains(body, "go_goroutines") {
		t.Error("Expected /metrics to contain go_goroutines")
	}
}

func TestMetrics_EndpointAfterHTTPRequests(t *testing.T) {
	ts := setupTestServer(t, TestConfig{
		Channels: `  "/user/{username}":
    stream:
      type: live
      path: "live/{username}"
      live_stream_key: "test-key"
      auth_token_template: "{username}"
      distribution:
        hls:
          segment_duration: 2
          window_size: 3
`,
	})

	// Make a few HTTP requests to generate activity
	for i := 0; i < 3; i++ {
		url := fmt.Sprintf("http://%s/user/testuser/master.m3u8", ts.HTTPAddr)
		resp := httpGet(t, url)
		resp.Body.Close()
	}

	// Verify metrics endpoint still works
	url := fmt.Sprintf("http://%s/metrics", ts.HTTPAddr)
	status, _ := httpGetBody(t, url)
	if status != http.StatusOK {
		t.Fatalf("Expected 200 from /metrics after requests, got %d", status)
	}
}
