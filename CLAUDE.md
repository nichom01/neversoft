<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
specs/004-kafka-batch-consumption/plan.md
<!-- SPECKIT END -->

## Running Tests

Quarkus was upgraded to 3.37.2 (from 3.15.3) to pick up a Byte Buddy version that supports Java 25. `mvn test` now runs fine on the system default JVM (Java 25) — no `JAVA_HOME` override needed.
