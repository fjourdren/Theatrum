# Ingest: RTMP and Restream

Two ways content gets in: pushed over RTMP, or pulled from an external URL. Ingest only —
Theatrum never serves RTMP to viewers.

## RTMP

Implemented in-tree under `adapter/in/rtmp/` (no JVM library is maintained, and the surface
Theatrum needs is small): `protocol/` for handshake, chunks, AMF0 and the connection loop,
`flv/` for muxing into FFmpeg's stdin, `handlers/` for auth and publish, `management/` for the
FFmpeg process, recording and thumbnails.

```
OBS / ffmpeg → RtmpServer (1935) → TheatrumRtmpHandler (connect: match channel pattern;
publish: XOR token check) → StreamManager → FlvWriter → FFmpeg stdin → HLS/DASH segments
→ StreamController (8080) → viewers
```

On stream end: cleanup (delete) or recording (VOD playlist + move to `record.path`).

## Authentication

Live streams authenticate with an XOR token. `live_stream_key` and `auth_token_template` are
required.

1. **connect** — the TCURL path (`/user/alice`) is matched against the channel patterns; the
   variables (`username=alice`) are extracted. This only proves the URL names a real channel.
2. **publish** — the template is filled in (`{username}` → `alice`), XORed with `live_stream_key`
   and hex-encoded. It must equal the publishing name (OBS's "stream key" field), or
   `AuthenticationException`.

`RtmpAuthService`:

```java
String xorString(String liveStreamKey, String input) {
    var key = liveStreamKey.getBytes(StandardCharsets.UTF_8);
    var result = input.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < result.length; i++) {
        result[i] ^= key[i % key.length];
    }
    return HexFormat.of().formatHex(result);
}
```

Cyclic key, **lowercase** hex, symmetric, byte-level UTF-8 (so non-ASCII XORs over more bytes
than it has characters — Go behaves identically).

A `{var}` in `auth_token_template` must exist in the channel pattern, or auth fails with a
missing-variable error. A placeholder spans exactly one path segment, never across `/`.

Computing a token:

```bash
python3 -c "
key=b'YOUR_KEY'; inp=bytearray(b'YOUR_INPUT')
for i in range(len(inp)): inp[i] ^= key[i % len(key)]
print(inp.hex())
"
```

Full URL: `rtmp://host/user/alice/<token>`.

### When it fails

Read the running `config.yml` → check the URL path matches the pattern exactly → work out which
variables it extracts → substitute into the template → recompute and compare. Then
`mvn verify -Dit.test=RtmpAuthTest`.

Usual causes: pattern mismatch (trailing slashes matter), a `{var}` the pattern does not extract,
uppercase hex client-side, an empty publishing name, a key that differs from the running config,
or a channel whose `type` is not `live` (auth never runs).

### Security

XOR is obfuscation, not encryption: anyone with the key can mint tokens, there is no replay
protection or rate limiting, and the key sits in plaintext in `config.yml` (gitignored for that
reason). Adequate against accidental publishing, not against an attacker.

## Restream

FFmpeg reads `source_url` directly instead of a pipe; everything downstream is identical to live.

- Auto-started for every restream channel at boot (`TheatrumRunner` → `RestreamManager`)
- Auto-reconnect with exponential backoff (1s → 30s max), reset after 30s of success
- Literal paths only — `{var}` is rejected, `{%FUNC%}` builtins are allowed
- Recording, viewers and views work the same as live
