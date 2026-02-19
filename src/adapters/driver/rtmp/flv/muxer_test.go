package flv

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestWriteTag_MuxerFunction(t *testing.T) {
	t.Run("writes from reader to writer", func(t *testing.T) {
		payload := []byte{0x01, 0x02, 0x03}
		reader := bytes.NewReader(payload)
		var writer bytes.Buffer

		err := WriteTag(9, 1000, reader, &writer)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		result := writer.Bytes()
		// 11 header + 3 payload + 4 prev tag size = 18
		if len(result) != 18 {
			t.Fatalf("expected 18 bytes, got %d", len(result))
		}

		// Tag type
		if result[0] != 9 {
			t.Errorf("expected tag type 9, got %d", result[0])
		}

		// Data size
		dataSize := uint32(result[1])<<16 | uint32(result[2])<<8 | uint32(result[3])
		if dataSize != 3 {
			t.Errorf("expected data size 3, got %d", dataSize)
		}

		// Payload
		if !bytes.Equal(result[11:14], payload) {
			t.Errorf("expected payload %v, got %v", payload, result[11:14])
		}

		// PreviousTagSize = 11 header + 3 data = 14
		prevSize := binary.BigEndian.Uint32(result[14:18])
		if prevSize != 14 {
			t.Errorf("expected prev tag size 14, got %d", prevSize)
		}
	})

	t.Run("audio tag type", func(t *testing.T) {
		payload := []byte{0xAA}
		var writer bytes.Buffer

		err := WriteTag(8, 0, bytes.NewReader(payload), &writer)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if writer.Bytes()[0] != 8 {
			t.Errorf("expected tag type 8, got %d", writer.Bytes()[0])
		}
	})

	t.Run("script tag type", func(t *testing.T) {
		payload := []byte{0xFF}
		var writer bytes.Buffer

		err := WriteTag(18, 0, bytes.NewReader(payload), &writer)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		if writer.Bytes()[0] != 18 {
			t.Errorf("expected tag type 18, got %d", writer.Bytes()[0])
		}
	})
}

func TestBufferWriter_Write(t *testing.T) {
	bw := NewBufferWriter(1024)
	var output bytes.Buffer

	payload := []byte{0x01, 0x02, 0x03, 0x04}
	err := bw.Write(9, 500, bytes.NewReader(payload), &output)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Should produce valid FLV tag
	result := output.Bytes()
	if len(result) != 19 { // 11 header + 4 data + 4 prev tag size
		t.Fatalf("expected 19 bytes, got %d", len(result))
	}
	if result[0] != 9 {
		t.Errorf("expected tag type 9, got %d", result[0])
	}
}

func TestBufferWriter_Reuse(t *testing.T) {
	bw := NewBufferWriter(64)
	var output bytes.Buffer

	// Write twice to test buffer reuse
	err := bw.Write(9, 100, bytes.NewReader([]byte{0x01}), &output)
	if err != nil {
		t.Fatalf("first write error: %v", err)
	}

	firstLen := output.Len()

	err = bw.Write(8, 200, bytes.NewReader([]byte{0x02, 0x03}), &output)
	if err != nil {
		t.Fatalf("second write error: %v", err)
	}

	// Output should contain both tags
	if output.Len() <= firstLen {
		t.Error("expected output to grow after second write")
	}
}

func TestNewBufferWriter(t *testing.T) {
	bw := NewBufferWriter(4096)
	if bw == nil {
		t.Fatal("expected non-nil BufferWriter")
	}
	if bw.buf == nil {
		t.Fatal("expected non-nil internal buffer")
	}
}
