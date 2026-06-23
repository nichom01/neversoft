# Implementation Plan: Smart CI Pipeline

**Branch**: `001-smart-ci-pipeline` | **Date**: 2026-06-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-smart-ci-pipeline/spec.md`

## Summary

Build a suite of composable GitHub Actions reusable workflows for a Java 21 / Quarkus monorepo that selectively builds and tests only the modules affected by a PR's changeset, and supports manual on-demand execution against any branch. Change detection uses native `git diff`; module execution uses GitHub Actions matrix strategy for parallelism; integration tests are gated on unit test success and environment readiness via Docker Compose.

## Technical Context

**Language/Version**: Java 21 (all services), YAML (workflow definitions)

**Primary Dependencies**: Maven 3.x, Quarkus 3.15.3, Maven Surefire 3.3.1 (unit tests), Maven Failsafe 3.3.1 (IT tests), Docker Compose (IT environment)

**Storage**: N/A (pipeline infrastructure, not application data)

**Testing**: Maven Surefire (unit), Maven Failsafe (integration), REST Assured + Awaitility (IT assertions)

**Target Platform**: GitHub Actions (ubuntu-latest runners), Java 21 runner setup via `actions/setup-java`

**Project Type**: CI/CD pipeline infrastructure (GitHub Actions workflows)

**Performance Goals**: Single-module PR pipeline completes in under half the time of a full run; PR checks appear within 2 minutes for single-module changes

**Constraints**: No root aggregator POM — each service has its own `pom.xml`; builds invoked per-directory. Docker available on ubuntu-latest runners.

**Scale/Scope**: 4 services + 1 IT module + 1 infra directory; pipeline must handle up to 6 modules in a matrix

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The constitution file contains only template placeholders — no project-specific principles have been established. No gates apply. Proceed.

## Project Structure

### Documentation (this feature)

```text
specs/001-smart-ci-pipeline/
├── plan.md              ← this file
├── spec.md              ← feature specification
├── research.md          ← Phase 0: decisions and rationale
├── data-model.md        ← Phase 1: logical entities and schemas
├── quickstart.md        ← Phase 1: validation scenarios
├── contracts/
│   └── workflow-contracts.md   ← Phase 1: workflow input/output contracts
└── tasks.md             ← Phase 2 output (created by /speckit-tasks)
```

### Source Code (repository root)

```text
.github/
└── workflows/
    ├── detect-changes.yml       # Reusable: determines affected modules from git diff
    ├── build-module.yml         # Reusable: builds a single Maven module
    ├── test-unit.yml            # Reusable: runs Surefire unit tests for one module
    ├── test-integration.yml     # Reusable: runs Failsafe IT suite (all services)
    ├── pr-pipeline.yml          # Orchestrator: triggered on pull_request events
    └── on-demand.yml            # Orchestrator: triggered via workflow_dispatch

infra/
├── docker-compose.yml           # Existing — used by test-integration.yml
└── debezium/                    # Existing

svc-declare/pom.xml              # Existing Maven module
svc-audit/pom.xml                # Existing Maven module
svc-validate/pom.xml             # Existing Maven module
svc-risk/pom.xml                 # Existing Maven module
integration-tests/pom.xml                 # Existing Maven IT module
```

**Structure Decision**: Single `.github/workflows/` directory containing both reusable workflows (prefixed by role: `detect-`, `build-`, `test-`) and orchestrating workflows (`pr-pipeline`, `on-demand`). No new source directories required — the pipeline infrastructure sits entirely within `.github/`.

## Complexity Tracking

No constitution violations identified. No complexity justification required.
