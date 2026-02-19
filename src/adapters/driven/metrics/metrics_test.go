package metrics

import (
	"net/http/httptest"
	"testing"
)

func TestNewResponseWriter(t *testing.T) {
	w := httptest.NewRecorder()
	rw := NewResponseWriter(w)

	if rw.StatusCode != 200 {
		t.Errorf("expected default status code 200, got %d", rw.StatusCode)
	}
	if rw.BytesWritten != 0 {
		t.Errorf("expected 0 bytes written, got %d", rw.BytesWritten)
	}
}

func TestResponseWriter_WriteHeader(t *testing.T) {
	w := httptest.NewRecorder()
	rw := NewResponseWriter(w)

	rw.WriteHeader(404)
	if rw.StatusCode != 404 {
		t.Errorf("expected status code 404, got %d", rw.StatusCode)
	}

	// Verify it was passed through to underlying writer
	if w.Code != 404 {
		t.Errorf("expected underlying writer status 404, got %d", w.Code)
	}
}

func TestResponseWriter_Write(t *testing.T) {
	w := httptest.NewRecorder()
	rw := NewResponseWriter(w)

	data1 := []byte("hello")
	n1, err := rw.Write(data1)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if n1 != 5 {
		t.Errorf("expected 5 bytes written, got %d", n1)
	}
	if rw.BytesWritten != 5 {
		t.Errorf("expected BytesWritten 5, got %d", rw.BytesWritten)
	}

	// Write more data and check accumulation
	data2 := []byte(" world")
	n2, err := rw.Write(data2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if n2 != 6 {
		t.Errorf("expected 6 bytes written, got %d", n2)
	}
	if rw.BytesWritten != 11 {
		t.Errorf("expected BytesWritten 11, got %d", rw.BytesWritten)
	}

	// Verify data was passed through
	if w.Body.String() != "hello world" {
		t.Errorf("expected body 'hello world', got %q", w.Body.String())
	}
}
