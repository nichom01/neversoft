# Feature Specification: Smart CI Pipeline

**Feature Branch**: `001-smart-ci-pipeline`

**Created**: 2026-06-22

**Status**: Draft

**Input**: User description: "i want to create a suite of github actions that i can chain together to form a build pipeline. The main principal is that only building the relevant artifacts and run tests for the area of the code that is affected on an PR/MR and have the flexibility to run test suites at any point against latest code"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Selective Build and Test on Pull Request (Priority: P1)

A developer opens a pull request that modifies one or more modules in the repository. The pipeline automatically detects which modules were changed, builds only those modules, and runs only the tests relevant to those modules. Unchanged modules are skipped entirely. The developer sees clear feedback in the PR about which modules were evaluated and the outcome of each.

**Why this priority**: This is the core value proposition — eliminating unnecessary build and test time on PRs while ensuring changed code is always validated. Every developer benefits from this on every PR.

**Independent Test**: Can be fully tested by opening a PR that changes files in a single module and verifying that only that module's build and tests are triggered, while other modules report as skipped.

**Acceptance Scenarios**:

1. **Given** a PR that modifies files in Module A only, **When** the pipeline runs, **Then** only Module A is built and tested; all other modules report as skipped with zero execution time
2. **Given** a PR that modifies files in Module A and Module B, **When** the pipeline runs, **Then** both Module A and Module B are built and tested independently; modules C, D, etc. are skipped
3. **Given** a PR that modifies shared configuration or root-level files, **When** the pipeline runs, **Then** all modules are built and tested (full pipeline triggered)
4. **Given** a module build failure, **When** the pipeline runs, **Then** the PR is marked as failed with a clear indication of which module failed and why

---

### User Story 2 - On-Demand Test Suite Execution (Priority: P2)

A developer or team lead wants to run a specific test suite (or all test suites) against any branch or commit at any time, not just on PR creation. They can trigger a test run from the GitHub Actions UI, selecting which modules or test categories to include, and targeting any branch.

**Why this priority**: This supports quality assurance workflows beyond the PR flow — pre-release validation, debugging a flaky environment, or verifying a hotfix before it merges.

**Independent Test**: Can be fully tested by manually triggering a workflow run from the GitHub Actions UI on any branch, selecting a specific module, and verifying the correct tests execute against the target branch's code.

**Acceptance Scenarios**:

1. **Given** a manually triggered workflow run, **When** the user selects "all modules" and a target branch, **Then** all modules are built and all tests run against the latest code on that branch
2. **Given** a manually triggered workflow run, **When** the user selects a specific module and a target branch, **Then** only that module is built and tested
3. **Given** a manually triggered workflow run targeting a branch with no recent changes, **When** the pipeline runs, **Then** it still executes successfully (not skipped, since this is an explicit request)
4. **Given** a test failure during on-demand execution, **When** the run completes, **Then** a summary report is available showing which tests passed, which failed, and diagnostic output

---

### User Story 3 - Composable Pipeline Chaining (Priority: P3)

A build engineer needs to assemble a custom pipeline by combining individual GitHub Actions building blocks in different orders or combinations — for example, "lint → build → unit test → integration test → deploy to staging". Each action in the suite is designed to be called independently or chained, with outputs from one action available as inputs to the next.

**Why this priority**: This is the extensibility story. It enables the team to adapt the pipeline as the project grows without rewriting core actions, and allows different flows for different scenarios (hotfix vs feature release vs nightly run).

**Independent Test**: Can be fully tested by authoring a new workflow file that calls two or more of the suite's reusable actions in sequence, verifying that outputs from the first action are correctly consumed by the second.

**Acceptance Scenarios**:

1. **Given** a workflow that chains "change detection" → "build" → "test" actions, **When** the workflow runs, **Then** outputs from change detection (the list of affected modules) are passed automatically to build and test actions
2. **Given** a workflow that calls only the "test" action directly (bypassing build), **When** the workflow runs, **Then** the action executes independently without requiring prior actions in the chain
3. **Given** a new action added to the suite, **When** a workflow chains it after an existing action, **Then** it receives standardised outputs from the previous action without requiring bespoke configuration

---

### Edge Cases

- What happens when a PR affects only documentation files (e.g., README, docs/)? — Pipeline should identify these as non-build changes and either skip all module builds or run a documentation-only check
- How does the pipeline behave when two PRs are open simultaneously affecting the same module? — Each PR's pipeline runs independently; no cross-PR coordination is required
- What happens when a module's test suite takes significantly longer than others? — Each module runs in its own job; long-running modules do not block others
- What happens if change detection itself fails (e.g., git history is unavailable)? — Pipeline falls back to running all modules (safe default) and reports that full-run mode was triggered due to detection failure
- What happens when a PR is rebased mid-run? — The in-flight run completes against the original commit; a new run is triggered automatically by GitHub for the updated commit

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The pipeline MUST detect which modules or areas of the repository are affected by the changes in a given PR, based on the file paths modified in the PR diff
- **FR-002**: The pipeline MUST build only the modules identified as affected, skipping unchanged modules
- **FR-003**: The pipeline MUST run only the test suites associated with affected modules, skipping tests for unchanged modules
- **FR-004**: Each module's build and test steps MUST execute in isolation, with failures in one module not preventing other modules from running
- **FR-005**: The pipeline MUST support manual (on-demand) triggering, allowing any branch to be targeted and any subset of modules to be selected
- **FR-006**: All pipeline actions MUST be composable — callable individually or chained together in any order within a GitHub Actions workflow
- **FR-007**: The pipeline MUST produce a clear summary report on completion, indicating which modules were built/tested, which were skipped, and which (if any) failed
- **FR-008**: When changes affect shared or root-level configuration, the pipeline MUST default to building and testing all modules
- **FR-009**: Each reusable action MUST expose its outputs (e.g., list of affected modules, build status, test results) in a standardised format for use by downstream actions in a chain
- **FR-010**: The pipeline MUST NOT require manual configuration to add a new module — adding a module to the repository structure should be automatically detected

### Key Entities

- **Module**: A discrete area of the codebase with its own build and test scope (e.g., a service, library, or application). Modules are identified by directory structure.
- **Change Set**: The set of file paths modified in a PR or explicitly provided at manual trigger time
- **Affected Modules**: The subset of modules whose source paths overlap with the Change Set
- **Pipeline Action**: A reusable GitHub Actions workflow or composite action that performs a single, well-defined step (detect, build, test, report)
- **Test Suite**: The collection of tests scoped to a specific module, runnable independently
- **Pipeline Run**: A single execution of a chain of Pipeline Actions, triggered either by a PR event or a manual dispatch

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A PR affecting a single module completes build and test in less than half the time of a full pipeline run across all modules
- **SC-002**: Developers can trigger an on-demand test run against any branch within 30 seconds of initiating the request from the GitHub Actions UI
- **SC-003**: 100% of changed modules are built and tested on every PR — no affected module is ever silently skipped
- **SC-004**: Adding a new module to the repository requires zero changes to existing pipeline configuration
- **SC-005**: Pipeline results are visible directly in the PR within 2 minutes of the PR being opened or updated (for single-module changes)
- **SC-006**: A build engineer can assemble a new pipeline workflow by combining existing actions with no more than one hour of effort

## Assumptions

- The repository is a monorepo where modules are distinguished by top-level or well-defined directory boundaries
- Module boundaries are deterministic from directory structure alone — no external registry or manifest is needed to identify them
- GitHub Actions is the target CI platform; all actions are authored as GitHub Actions workflows or composite actions
- Test suites already exist for each module; this feature is about selectively running them, not authoring them
- The team uses standard PR-based workflows (not direct pushes to main) as the primary integration gate
- Shared/root-level changes (e.g., global config, CI infrastructure itself) are treated conservatively — they trigger full pipeline runs
- Parallel execution of independent module jobs is supported and desirable; the pipeline should exploit this where the platform allows
- Each module's build and test is self-contained — one module's tests do not depend on another module being built first (no cross-module test dependencies at runtime)
