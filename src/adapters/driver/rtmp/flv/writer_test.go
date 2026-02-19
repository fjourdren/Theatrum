package flv

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestWriter_WriteHeader(t *testing.T) {
	t.Run("writes FLV signature", func(t *testing.T) {
		var buf bytes.Buffer
		w := NewWriter(&buf)
		w.WriteHeader()

		data := buf.Bytes()
		if len(data) != 13 {
			t.Fatalf("expected 13 bytes header, got %d", len(data))
		}
		// FLV signature
		if data[0] != 'F' || data[1] != 'L' || data[2] != 'V' {
			t.Error("expected FLV signature")
		}
		// Version
		if data[3] != 0x01 {
			t.Errorf("expected version 1, got %d", data[3])
		}
		// Flags (audio + video)
		if data[4] != 0x05 {
			t.Errorf("expected flags 0x05, got 0x%02x", data[4])
		}
	})

	t.Run("only writes once", func(t *testing.T) {
		var buf bytes.Buffer
		w := NewWriter(&buf)
		w.WriteHeader()
		w.WriteHeader()
		w.WriteHeader()

		// Should still be 13 bytes (written only once)
		if buf.Len() != 13 {
			t.Errorf("expected 13 bytes (header written once), got %d", buf.Len())
		}
	})
}

func TestWriter_WriteTag(t *testing.T) {
	t.Run("correct tag header bytes", func(t *testing.T) {
		var buf bytes.Buffer
		w := NewWriter(&buf)

		data := []byte("hello")
		err := w.WriteTag(9, 1000, data)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		result := buf.Bytes()
		// 11 byte header + 5 byte data + 4 byte prev tag size = 20
		if len(result) != 20 {
			t.Fatalf("expected 20 bytes, got %d", len(result))
		}

		// Tag type
		if result[0] != 9 {
			t.Errorf("expected tag type 9, got %d", result[0])
		}

		// Data size (3 bytes big endian)
		dataSize := uint32(result[1])<<16 | uint32(result[2])<<8 | uint32(result[3])
		if dataSize != 5 {
			t.Errorf("expected data size 5, got %d", dataSize)
		}

		// Timestamp (lower 24 bits)
		ts := uint32(result[4])<<16 | uint32(result[5])<<8 | uint32(result[6])
		if ts != 1000&0xFFFFFF {
			t.Errorf("expected timestamp lower 24 bits %d, got %d", 1000&0xFFFFFF, ts)
		}

		// Timestamp extended byte
		if result[7] != byte(1000>>24) {
			t.Errorf("expected timestamp extended 0, got %d", result[7])
		}

		// Stream ID (always 0)
		if result[8] != 0 || result[9] != 0 || result[10] != 0 {
			t.Error("expected stream ID 0")
		}

		// Data
		if string(result[11:16]) != "hello" {
			t.Errorf("expected data 'hello', got %q", result[11:16])
		}

		// Previous tag size
		prevTagSize := binary.BigEndian.Uint32(result[16:20])
		if prevTagSize != 16 { // 11 header + 5 data
			t.Errorf("expected prev tag size 16, got %d", prevTagSize)
		}
	})

	t.Run("timestamp extended byte", func(t *testing.T) {
		var buf bytes.Buffer
		w := NewWriter(&buf)

		// Use a timestamp > 0xFFFFFF to test extended byte
		timestamp := uint32(0x01ABCDEF)
		err := w.WriteTag(9, timestamp, []byte{0x00})
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		result := buf.Bytes()
		// Extended byte should be 0x01
		if result[7] != 0x01 {
			t.Errorf("expected timestamp extended byte 0x01, got 0x%02x", result[7])
		}
	})
}

func TestWriter_WriteAudio(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)

	err := w.WriteAudio(500, []byte{0xAA, 0xBB})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	result := buf.Bytes()
	// Should have header (13) + tag header (11) + data (2) + prev tag size (4) = 30
	if len(result) != 30 {
		t.Fatalf("expected 30 bytes, got %d", len(result))
	}

	// Tag type should be 8 (audio) at offset 13
	if result[13] != 8 {
		t.Errorf("expected audio tag type 8, got %d", result[13])
	}
}

func TestWriter_WriteVideo(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)

	err := w.WriteVideo(1000, []byte{0x01, 0x02, 0x03})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	result := buf.Bytes()
	// Header auto-written: 13 + tag (11 + 3 + 4) = 31
	if len(result) != 31 {
		t.Fatalf("expected 31 bytes, got %d", len(result))
	}

	// Tag type should be 9 (video) at offset 13
	if result[13] != 9 {
		t.Errorf("expected video tag type 9, got %d", result[13])
	}
}

func TestWriter_WriteScript(t *testing.T) {
	var buf bytes.Buffer
	w := NewWriter(&buf)

	err := w.WriteScript(0, []byte{0xFF})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	result := buf.Bytes()
	// Header auto-written: 13 + tag (11 + 1 + 4) = 29
	if len(result) != 29 {
		t.Fatalf("expected 29 bytes, got %d", len(result))
	}

	// Tag type should be 18 (script) at offset 13
	if result[13] != 18 {
		t.Errorf("expected script tag type 18, got %d", result[13])
	}
}

func TestMakeFLVTagHeader(t *testing.T) {
	tests := []struct {
		name      string
		tagType   byte
		dataSize  uint32
		timestamp uint32
	}{
		{"video small ts", 9, 100, 500},
		{"audio zero ts", 8, 50, 0},
		{"script large ts", 18, 200, 0x01234567},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			header := makeFLVTagHeader(tt.tagType, tt.dataSize, tt.timestamp)
			if len(header) != 11 {
				t.Fatalf("expected 11 bytes, got %d", len(header))
			}

			if header[0] != tt.tagType {
				t.Errorf("expected tag type %d, got %d", tt.tagType, header[0])
			}

			// Data size (3 bytes big endian)
			size := uint32(header[1])<<16 | uint32(header[2])<<8 | uint32(header[3])
			if size != tt.dataSize {
				t.Errorf("expected data size %d, got %d", tt.dataSize, size)
			}

			// Timestamp lower 24 bits
			ts := uint32(header[4])<<16 | uint32(header[5])<<8 | uint32(header[6])
			expectedTS := tt.timestamp & 0xFFFFFF
			if ts != expectedTS {
				t.Errorf("expected timestamp %d, got %d", expectedTS, ts)
			}

			// Extended byte
			if header[7] != byte(tt.timestamp>>24) {
				t.Errorf("expected extended byte 0x%02x, got 0x%02x", byte(tt.timestamp>>24), header[7])
			}

			// Stream ID always 0
			if header[8] != 0 || header[9] != 0 || header[10] != 0 {
				t.Error("expected stream ID 0")
			}
		})
	}
}
