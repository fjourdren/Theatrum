---
name: rtmp-auth
description: Explain and debug the XOR-based RTMP authentication system
allowed-tools: Read, Grep, Glob, Bash
---

# RTMP Authentication

Explain or debug Theatrum's XOR auth. `$ARGUMENTS` focuses (e.g. `/rtmp-auth why is my token
rejected`, `/rtmp-auth compute token for username=bob key=mysecret`).

## Read first

`docs/ingest.md` § Authentication — the flow, the XOR implementation, pattern-to-regex rules,
token templates, the failure table and the security properties.

Then the source, for anything the doc does not settle:

- `domain/service/RtmpAuthService.java` — XOR, pattern matching, token validation
- `application/port/in/AuthorizePublishUseCase.java` / `port/in/exception/AuthenticationException.java`
- `infrastructure/adapter/in/rtmp/handlers/TheatrumRtmpHandler.java` — where auth is invoked
- `domain/service/PathTemplateService.java` — the template/regex machinery
- `domain/model/Stream.java` — `liveStreamKey()`, `authTokenTemplate()`

Tests are executable documentation here: `domain/service/RtmpAuthServiceTest.java` and
`e2e/RtmpAuthTest.java`.

## Debugging order

1. Read the running `config.yml` — channel pattern, `live_stream_key`, `auth_token_template`
2. Verify the RTMP URL path matches the pattern exactly
3. Work out which variables the pattern extracts
4. Substitute them into the template to get the XOR input
5. Compute the expected token with Bash and compare with what the client sent — faster than
   reasoning about it
6. Still failing: `mvn verify -Dit.test=RtmpAuthTest`
