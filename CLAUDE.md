<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
specs/002-event-consumer-map/plan.md
<!-- SPECKIT END -->

## Running Tests

The system JVM is Java 25, but Quarkus 3.15.3's Byte Buddy only supports up to Java 23. All `mvn test` commands must be run with `JAVA_HOME` pointing to the Java 21 installation:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.8/libexec/openjdk.jdk/Contents/Home mvn test
```

Tests will fail with a Byte Buddy `Java 25 is not supported` error if run with the default JVM.
