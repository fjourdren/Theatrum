---
name: write-tests
description: Write Go unit and e2e tests following Theatrum's exact conventions
---

# Go Test Writing Specialist

You write Go tests for Theatrum following the project's exact conventions. When invoked, write tests for the file or package specified in `$ARGUMENTS`. If no argument is given, ask what to test.

## Step 1: Read Existing Tests

Before writing any test, read these reference files to match the project's style:

- `src/domain/services/pathTemplateService_test.go` — Table-driven tests, subtests, error cases (most comprehensive)
- `src/domain/services/viewerTracker_test.go` — Mock storage, time-dependent behavior, helper constructors
- `src/adapters/driven/metrics/viewerCollector_test.go` — Prometheus testutil usage
- `src/adapters/driven/ffmpegEncoder/repositories/ffmpegEncoder_test.go` — DryRun mode testing

Also read the file under test and any port interfaces it depends on.

## Step 2: Read the Target File

Read the file specified in `$ARGUMENTS` and understand:
- All exported and unexported functions
- Dependencies (injected via constructor)
- Port interfaces used
- Error paths and edge cases

## Conventions (MANDATORY)

### Testing Framework
- Standard `testing` package ONLY
- **NO testify** (no `assert`, no `require`, no `suite`)
- **NO gomock** or any mock generation library
- **NO `testing/fstest`** or other non-standard test helpers

### File Organization
- Test file: same directory, same package (NOT `_test` external package)
- File name: `{originalFileName}_test.go`
- Module path: `Theatrum` (e.g., `import "Theatrum/domain/models"`)

### Test Structure
- **Table-driven tests** with `t.Run(tt.name, ...)`:
```go
func TestFunctionName_Behavior(t *testing.T) {
    tests := []struct {
        name     string
        input    string
        expected string
        wantErr  bool
    }{
        {
            name:     "descriptive case name",
            input:    "value",
            expected: "result",
        },
        {
            name:    "error case description",
            input:   "bad",
            wantErr: true,
        },
    }

    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            result, err := FunctionUnderTest(tt.input)
            if tt.wantErr {
                if err == nil {
                    t.Fatalf("expected error, got nil")
                }
                return
            }
            if err != nil {
                t.Fatalf("unexpected error: %v", err)
            }
            if result != tt.expected {
                t.Errorf("got %q, want %q", result, tt.expected)
            }
        })
    }
}
```

### Assertions
- Use `t.Errorf()` for non-fatal assertions (test continues)
- Use `t.Fatalf()` for fatal assertions (test stops)
- Always include descriptive messages: `t.Errorf("got %v, want %v", got, want)`

### Mocking
- Manual mock structs implementing port interfaces
- Define mocks in the test file, NOT in separate files
- Example pattern from `viewerTracker_test.go`:

```go
type mockStorage struct{}

func (m *mockStorage) ReadFile(path string) ([]byte, error)                          { return nil, nil }
func (m *mockStorage) WriteFile(path string, data []byte) error                      { return nil }
func (m *mockStorage) DeleteFile(path string) error                                  { return nil }
func (m *mockStorage) ListFiles(pattern string) ([]string, error)                    { return nil, nil }
func (m *mockStorage) GetFileSize(path string) (int64, error)                        { return 0, nil }
func (m *mockStorage) SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error) {
    return nil, nil, nil
}
```

For mocks that need configurable behavior, use function fields:

```go
type mockStorageWithBehavior struct {
    readFileFunc  func(path string) ([]byte, error)
    writeFileFunc func(path string, data []byte) error
    // ... other methods
}

func (m *mockStorageWithBehavior) ReadFile(path string) ([]byte, error) {
    if m.readFileFunc != nil {
        return m.readFileFunc(path)
    }
    return nil, nil
}
```

### Port Interfaces to Mock

**StoragePort** (`src/domain/repositories/storagePort.go`):
```go
type StoragePort interface {
    ReadFile(path string) ([]byte, error)
    WriteFile(path string, data []byte) error
    DeleteFile(path string) error
    ListFiles(pattern string) ([]string, error)
    GetFileSize(path string) (int64, error)
    SearchFiles(pattern string, extensions []string) ([]string, []map[string]string, error)
}
```

**EncoderPort** (`src/domain/repositories/encoderPort.go`):
```go
type EncoderPort interface {
    EncodeVideo(inputPath string, outputPath string, qualities map[string]models.Quality, distribution models.Distribution) error
}
```

**Note:** `models.Distribution` uses pointer fields (`*Hls`, `*Dash`). In tests, construct as:
```go
dist := models.Distribution{Hls: &models.Hls{SegmentDuration: 6}}
dist := models.Distribution{Dash: &models.Dash{SegmentDuration: 6}}
dist := models.Distribution{Hls: &models.Hls{SegmentDuration: 2}, Dash: &models.Dash{SegmentDuration: 2}} // dual
```

**ConfigurationPort** (`src/domain/repositories/configurationPort.go`):
```go
type ConfigurationPort interface {
    Load(configPath string) (*models.Application, *models.Server, *map[string]models.Stream, error)
}
```

### Helper Constructors

When a struct's `New*()` constructor starts goroutines or has side effects, create a test helper that directly initializes the struct:

```go
// newTestTracker creates a ViewerTracker without starting the cleanup goroutine.
func newTestTracker() *ViewerTracker {
    return &ViewerTracker{
        streams: make(map[string]*streamTracking),
        storage: &mockStorage{},
    }
}
```

## Step 3: Write Tests

Priority order:
1. **Happy path** — Normal expected behavior
2. **Error cases** — Invalid input, missing data, failed dependencies
3. **Edge cases** — Empty input, nil values, boundary conditions
4. **Concurrency** (if applicable) — Race conditions, concurrent access

## Step 4: Run Tests

After writing tests, run them:

```bash
cd src && go test ./... -v -run TestNamePattern
```

For a specific package:
```bash
cd src && go test ./domain/services/ -v -run TestFunctionName
```

For race detection:
```bash
cd src && go test ./... -race
```

Fix any compilation or test failures before finishing.
