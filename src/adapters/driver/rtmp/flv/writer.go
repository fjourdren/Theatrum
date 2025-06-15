package flv

import (
	"encoding/binary"
	"io"
	"sync"
)

// Writer handles FLV tag writing with thread safety
type Writer struct {
	writer    io.Writer
	writeMutex sync.Mutex
	headerOnce sync.Once
}

// NewWriter creates a new FLV writer
func NewWriter(writer io.Writer) *Writer {
	return &Writer{
		writer: writer,
	}
}

// WriteHeader writes the FLV header (only once)
func (w *Writer) WriteHeader() {
	w.headerOnce.Do(func() {
		header := []byte{'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00}
		_, _ = w.writer.Write(header)
	})
}

// WriteTag writes a properly formatted FLV tag
func (w *Writer) WriteTag(tagType byte, timestamp uint32, data []byte) error {
	w.writeMutex.Lock()
	defer w.writeMutex.Unlock()

	dataSize := uint32(len(data))
	tagHeader := makeFLVTagHeader(tagType, dataSize, timestamp)

	// Write tag header
	if _, err := w.writer.Write(tagHeader); err != nil {
		return err
	}

	// Write tag data
	if _, err := w.writer.Write(data); err != nil {
		return err
	}

	// Write previous tag size
	prevTagSize := make([]byte, 4)
	binary.BigEndian.PutUint32(prevTagSize, dataSize+11)
	if _, err := w.writer.Write(prevTagSize); err != nil {
		return err
	}

	return nil
}

// WriteAudio writes an audio tag
func (w *Writer) WriteAudio(timestamp uint32, data []byte) error {
	w.WriteHeader()
	return w.WriteTag(8, timestamp, data) // FLV audio tag type = 8
}

// WriteVideo writes a video tag
func (w *Writer) WriteVideo(timestamp uint32, data []byte) error {
	w.WriteHeader()
	return w.WriteTag(9, timestamp, data) // FLV video tag type = 9
}

// WriteScript writes a script tag (metadata)
func (w *Writer) WriteScript(timestamp uint32, data []byte) error {
	w.WriteHeader()
	return w.WriteTag(18, timestamp, data) // FLV script tag type = 18
}

// makeFLVTagHeader creates an FLV tag header
func makeFLVTagHeader(tagType byte, dataSize uint32, timestamp uint32) []byte {
	header := make([]byte, 11)
	header[0] = tagType
	header[1] = byte(dataSize >> 16)
	header[2] = byte(dataSize >> 8)
	header[3] = byte(dataSize)
	header[4] = byte(timestamp >> 16)
	header[5] = byte(timestamp >> 8)
	header[6] = byte(timestamp)
	header[7] = byte(timestamp >> 24)
	header[8] = 0
	header[9] = 0
	header[10] = 0
	return header
} 