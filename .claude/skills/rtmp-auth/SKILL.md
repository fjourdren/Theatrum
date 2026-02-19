---
name: rtmp-auth
description: Explain and debug the XOR-based RTMP authentication system
allowed-tools: Read, Grep, Glob, Bash
---

# RTMP Authentication Deep Dive

You are an expert on Theatrum's RTMP authentication system. Use `$ARGUMENTS` to focus on specific issues (e.g., `/rtmp-auth why is my token rejected` or `/rtmp-auth compute token for username=bob key=mysecret`).

## Source Files to Read

Always read these before answering:

- `src/domain/services/rtmpAuthService.go` — Core auth logic (XOR, pattern matching, token validation)
- `src/adapters/driver/rtmp/auth/pattern.go` — URL pattern-to-regex conversion (legacy, being moved to domain)
- `src/adapters/driver/rtmp/handlers/handler.go` — OnConnect/OnPublish where auth is invoked
- `src/domain/models/stream.go` — Stream model with `LiveStreamKey` and `AuthTokenTemplate` fields

## Authentication Flow

```
RTMP Client (OBS, FFmpeg, etc.)
  │
  │  Connect: rtmp://server:1935/user/alice
  │
  ▼
┌─────────────────────────────────────────────┐
│ Handler.OnConnect()                          │
│                                              │
│ 1. Extract TCURL from connect command        │
│    tcurl = "rtmp://server:1935/user/alice"   │
│                                              │
│ 2. Parse URL → extract path                  │
│    path = "/user/alice"                      │
│                                              │
│ 3. Match against channel patterns            │
│    pattern: "/user/{username}"               │
│    regex:   ^/user/(?P<username>[^/]+)$      │
│                                              │
│ 4. Extract variables                         │
│    vars = { "username": "alice" }            │
│                                              │
│ 5. IsAuthorized() → true (pattern matched)   │
└─────────────────┬───────────────────────────┘
                  │
                  │  Publish: publishingName = "<hex_token>"
                  ▼
┌─────────────────────────────────────────────┐
│ Handler.OnPublish()                          │
│                                              │
│ 1. ExtractChannel(tcurl) → stream, vars      │
│                                              │
│ 2. Build XOR input from auth_token_template  │
│    template: "{username}"                    │
│    input:    "alice"                         │
│                                              │
│ 3. XOR(input, live_stream_key)               │
│    key:    "your-secure-rtmp-secret-key"     │
│    result: XOR each byte cyclically          │
│    token:  hex.EncodeToString(result)        │
│                                              │
│ 4. Compare: publishingName == expectedToken  │
│    Match → allow publish                     │
│    Mismatch → reject with error              │
└─────────────────────────────────────────────┘
```

## XOR Implementation Details

From `rtmpAuthService.go`:

```go
func (s *RtmpAuthService) xorString(liveStreamKey string, input string) string {
    inputBytes := []byte(input)
    result := make([]byte, len(inputBytes))
    for i := 0; i < len(inputBytes); i++ {
        result[i] = inputBytes[i] ^ liveStreamKey[i%len(liveStreamKey)]
    }
    return hex.EncodeToString(result)
}
```

Key properties:
- **Cyclic key**: The key wraps around (`i % len(key)`) — short keys repeat
- **Hex output**: Result is hex-encoded (2 chars per byte, so output length = 2 * input length)
- **Symmetric**: `XOR(XOR(input, key), key) == input` — same operation encrypts and decrypts
- **Byte-level**: Operates on raw bytes, not unicode codepoints

## Pattern-to-Regex Conversion

Channel URL patterns are converted to regexes for matching:

| Pattern | Regex | Variables |
|---------|-------|-----------|
| `/user/{username}` | `^/user/(?P<username>[^/]+)$` | `["username"]` |
| `/room/{room_id}/{username}` | `^/room/(?P<room_id>[^/]+)/(?P<username>[^/]+)$` | `["room_id", "username"]` |
| `/static/live` | `^/static/live$` | `[]` |

The conversion:
1. `regexp.QuoteMeta(pattern)` — escape all regex special chars
2. Replace `\{varname\}` with `(?P<varname>[^/]+)` — named capture group matching any non-slash chars
3. Wrap with `^...$` for full-string matching

## Auth Token Template

The `auth_token_template` field controls what gets XORed with the key:

| Template | Variables | XOR Input |
|----------|-----------|-----------|
| `{username}` | `username=alice` | `alice` |
| `{room_id}{username}` | `room_id=42, username=alice` | `42alice` |
| `{username}-{room_id}` | `username=alice, room_id=42` | `alice-42` |

The template uses `{var}` placeholders that are replaced with URL-extracted values. Literal characters in the template (like `-`) are kept as-is.

## Computing Tokens

### Python (for testing)

```python
def compute_token(key: str, auth_input: str) -> str:
    result = bytes([ord(c) ^ ord(key[i % len(key)]) for i, c in enumerate(auth_input)])
    return result.hex()

# Example: key="testkey123", template="{username}", username="alice"
token = compute_token("testkey123", "alice")
print(token)  # Use this as publishingName in RTMP URL
```

### Go

```go
func computeToken(key, input string) string {
    inputBytes := []byte(input)
    result := make([]byte, len(inputBytes))
    for i := 0; i < len(inputBytes); i++ {
        result[i] = inputBytes[i] ^ key[i%len(key)]
    }
    return hex.EncodeToString(result)
}
```

### Bash (one-liner for quick tests)

```bash
python3 -c "
key='YOUR_KEY'; inp='YOUR_INPUT'
print(bytes([ord(c)^ord(key[i%len(key)]) for i,c in enumerate(inp)]).hex())
"
```

## Common Issues

| Issue | Symptom | Cause | Fix |
|-------|---------|-------|-----|
| Pattern mismatch | `OnConnect` rejects | TCURL path doesn't match any channel pattern | Check URL matches a pattern in `channels:` config. Trailing slashes matter. |
| Missing variable | `authentication failed: missing variables in URL` | Template references a `{var}` not present in the URL pattern | Ensure all `{var}` in `auth_token_template` exist in the channel URL pattern |
| Wrong token | `invalid authentication token` | Token doesn't match expected XOR result | Recompute with correct key, template, and extracted variables |
| Empty publishingName | `empty publishingName provided` | Client didn't send a stream key / publishing name | Configure OBS/FFmpeg to include the token as the stream key |
| Key mismatch | `invalid authentication token` | Config key differs from what was used to compute token | Verify `live_stream_key` in config matches the key used client-side |
| Encoding mismatch | Token looks right but doesn't match | Key or input has non-ASCII chars or different encoding | Ensure both sides use UTF-8 byte representation |

## Security Considerations

- **XOR is not cryptographic** — it's obfuscation, not encryption. Anyone who knows the key can compute valid tokens.
- **No replay protection** — the same token works forever (until the key changes). There's no nonce or timestamp.
- **Key in plaintext config** — `live_stream_key` is stored in `config.yml` in plaintext.
- **No rate limiting** — brute-force attempts are not throttled at the auth level.
- **Adequate for private streams** — sufficient when the goal is preventing accidental unauthorized streaming, not defending against determined attackers.

## Debugging Workflow

When a user reports auth failures:

1. **Read the config** — check the channel pattern, `live_stream_key`, and `auth_token_template`
2. **Verify the RTMP URL** — does the path match the channel pattern exactly?
3. **Extract variables** — what values would the regex extract from the URL?
4. **Build the auth input** — substitute variables into the template
5. **Compute expected token** — XOR the auth input with the key, hex-encode
6. **Compare** — does the client's publishing name match?

Use Bash to compute tokens interactively when debugging.
