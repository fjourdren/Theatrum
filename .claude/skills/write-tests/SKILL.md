---
name: write-tests
description: Write JUnit 5 unit and E2E tests following Theatrum's exact conventions
---

# Test Writing Specialist

Write tests for the behaviour named in `$ARGUMENTS`. If nothing is given, ask what to test.

**Theatrum is TDD-only.** The normal case is a test for code that does not exist yet: write it,
run it, watch it fail for the right reason, and stop — the failing test is the deliverable. Only
write the production code if asked to in the same breath. Back-filling tests onto existing
untested code is the exception, not the default; say so when that is what you are doing.

## Step 1: Read the conventions

`docs/testing.md` is mandatory reading — frameworks, file organisation, mock-vs-fake, `@Nested`
and `@MethodSource` structure, the `Clock` seam, `@TempDir`, port signatures, record shapes,
coverage priority, E2E specifics. Follow it exactly.

## Step 2: Read the reference tests

The table in `docs/testing.md` § Reference tests names which one matches what you are writing.
Read that one before writing.

## Step 3: Pin down the subject

**Writing first (normal):** design the API from the call site — the test is the first consumer.
Name the class, its constructor dependencies (always constructor-injected, ports not concretes)
and the behaviour under test. Read the ports it will need so the fakes match real signatures.

**Back-filling (exception):** read the existing class — public API, constructor dependencies,
which ports it uses, its error paths. With `@RequiredArgsConstructor` the constructor is not in
the source; the parameters are the `private final` fields in declaration order.

## Step 4: Write, then run

```bash
mvn test -Dtest={ClassName}Test
mvn test                              # all unit tests
mvn verify                            # + e2e, needs ffmpeg
```

Writing first: the run must **fail**, and the failure message must be the one the behaviour
predicts — a compile error or a wrong-reason failure means the test is not pinning anything yet.
Report that red as the result; do not "fix" it by writing the implementation unasked.

Once implementing: fix every compilation error and failure before finishing. If you touched
`domain/` or `application/`, also run `mvn test -Dtest=ArchitectureTest`.
