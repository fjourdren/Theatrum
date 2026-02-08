// -----------------------------------------------------------------------------
// Package flv provides helpers to serialise a single FLV tag (header + payload
// + PreviousTagSize) to an io.Writer.  It is intentionally alloc‑free and
// minimal, perfect for piping live FLV straight into FFmpeg (pipe:0).
// -----------------------------------------------------------------------------
package flv

import (
	"bytes"
	"encoding/binary"
	"io"
)

// WriteTag writes exactly one FLV tag to w.
//   tagType   – 8 (audio), 9 (video) or 18 (metadata/AMF)
//   timestamp – DTS in milliseconds (TimestampExtended is handled internally)
//   r         – payload reader (AAC frame, H.264 NALs, AMF object …)
//   w         – destination writer (e.g. FFmpeg's stdin)
func WriteTag(tagType uint8, timestamp uint32, r io.Reader, w io.Writer) error {
    payload, err := io.ReadAll(r) // slurp to know size up‑front (24‑bit field)
    if err != nil {
        return err
    }
    dataSize := len(payload)

    // ---------- 11‑byte Tag Header ----------
    var hdr [11]byte
    hdr[0] = tagType
    hdr[1] = byte(dataSize >> 16)
    hdr[2] = byte(dataSize >> 8)
    hdr[3] = byte(dataSize)
    hdr[4] = byte(timestamp >> 16)
    hdr[5] = byte(timestamp >> 8)
    hdr[6] = byte(timestamp)
    hdr[7] = byte(timestamp >> 24) // TimestampExtended
    // hdr[8:11] StreamID always 0

    if _, err = w.Write(hdr[:]); err != nil {
        return err
    }
    if _, err = w.Write(payload); err != nil {
        return err
    }

    // ---------- PreviousTagSize ----------
    var prev [4]byte
    binary.BigEndian.PutUint32(prev[:], uint32(len(hdr)+dataSize))
    _, err = w.Write(prev[:])
    return err
}

// BufferWriter reuses an internal buffer across calls so you avoid allocations
// on every frame.
//
//     bw := flv.NewBufferWriter(64*1024)
//     bw.Write(9, dts, r, ffmpegStdin)
//
// If the payload is larger than the buffer the buffer is grown automatically.
type BufferWriter struct{ buf *bytes.Buffer }

func NewBufferWriter(capacity int) *BufferWriter {
    return &BufferWriter{buf: bytes.NewBuffer(make([]byte, 0, capacity))}
}

func (bw *BufferWriter) Write(tagType uint8, timestamp uint32, r io.Reader, w io.Writer) error {
    bw.buf.Reset()
    if _, err := bw.buf.ReadFrom(r); err != nil {
        return err
    }
    return WriteTag(tagType, timestamp, bytes.NewReader(bw.buf.Bytes()), w)
} 