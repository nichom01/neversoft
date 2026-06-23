# Tasks: Smart CI Pipeline

**Input**: Design documents from `specs/001-smart-ci-pipeline/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/workflow-contracts.md

**Tests**: Not included — no TDD approach was requested. Validation is performed manually against the running pipeline using `quickstart.md` scenarios.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

---

## Phase 1: Setup

**Purpose**: Create the GitHub Actions directory structure required before any workflow files can be authored.

- [x] T001 Create `.github/workflows/` directory at repository root

---

## Phase 2: Foundational (Blocking Prerequisite)

**Purpose**: The change-detection workflow is the keystone — all orchestrating workflows consume its outputs. No user story can be validated until this is complete.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T002 Implement `.github/workflows/detect-changes.yml` as a `workflow_call` reusable workflow with: inputs `modules` (string, default `"all"`) and `base_ref` (string, optional); hardcoded known-module list `[svc-declare, svc-audit, svc-validate, svc-risk, integration-tests, infra]`; git diff logic (`git diff --name-only ${{ inputs.base_ref }}...HEAD`) to filter changed paths by module prefix; full-fallback logic (set `detection_source: full-fallback` and return full list if git diff exits non-zero); outputs `affected_modules` (JSON array string), `run_all` (string `"true"/"false"`), `run_it_tests` (string `"true"/"false"`), `detection_source` (string `detected|manual|full-fallback`); `run_it_tests` set to `"true"` when any of the four services, `infra`, or `integration-tests` appear in affected modules; when `inputs.modules` is provided and not `"all"`, return it directly as the affected list with `detection_source: manual`

**Checkpoint**: Run `detect-changes.yml` standalone via `workflow_dispatch` on a test branch to confirm outputs before proceeding.

---

## Phase 3: User Story 1 - Selective Build and Test on PR (Priority: P1) 🎯 MVP

**Goal**: A PR that touches one or more modules triggers builds and unit tests only for those modules, plus integration tests if any service or infra module changed. Unaffected modules are skipped.

**Independent Test**: Open a PR modifying a single file in `svc-declare/`. Confirm only `svc-declare` appears in build and unit-test matrix jobs. Confirm integration tests run. Confirm all other services report as skipped.

### Implementation for User Story 1

- [x] T003 [P] [US1] Implement `.github/workflows/build-module.yml` as a `workflow_call` reusable workflow with: inputs `module` (string, required) and `ref` (string, optional); `actions/checkout@v4` (with `ref: ${{ inputs.ref }}` when provided); `actions/setup-java@v4` with `java-version: '21'` and `distribution: 'temurin'`; step running `mvn -f ${{ inputs.module }}/pom.xml package -DskipTests`; job outputs `status` (string `passed|failed` derived from step outcome) and `duration_s` (number)
- [x] T004 [P] [US1] Implement `.github/workflows/test-unit.yml` as a `workflow_call` reusable workflow with: inputs `module` (string, required) and `ref` (string, optional); `actions/checkout@v4`; `actions/setup-java@v4` Java 21 temurin; step running `mvn -f ${{ inputs.module }}/pom.xml test`; step parsing Surefire XML reports from `${{ inputs.module }}/target/surefire-reports/` to extract test counts; job outputs `status`, `tests_run`, `tests_failed`, `duration_s`
- [x] T005 [US1] Implement `.github/workflows/test-integration.yml` as a `workflow_call` reusable workflow with: input `ref` (string, optional); `actions/checkout@v4`; `actions/setup-java@v4` Java 21 temurin; step `docker compose -f infra/docker-compose.yml up -d --wait` (using Docker's built-in `--wait` flag for health-check readiness); step running `mvn -f integration-tests/pom.xml verify -Dsurefire.skip=true`; teardown step (always runs) `docker compose -f infra/docker-compose.yml down`; job outputs `status`, `tests_run`, `tests_failed`, `duration_s`
- [x] T006 [US1] Implement `.github/workflows/pr-pipeline.yml` as an orchestrating workflow with: `on: pull_request` trigger (types: `[opened, synchronize, reopened]`); `concurrency` group `${{ github.workflow }}-${{ github.ref }}` with `cancel-in-progress: true`; `detect` job calling `./.github/workflows/detect-changes.yml` using `base_ref: ${{ github.event.pull_request.base.sha }}`; `build` job with `strategy.matrix.module: ${{ fromJson(needs.detect.outputs.affected_modules) }}` calling `./.github/workflows/build-module.yml` with `module: ${{ matrix.module }}`; `unit-test` job with same matrix strategy calling `./.github/workflows/test-unit.yml`; `integration` job calling `./.github/workflows/test-integration.yml` gated on `needs.detect.outputs.run_it_tests == 'true'`; inline `report` job (using `github-script` or shell) that posts a markdown summary to `$GITHUB_STEP_SUMMARY` showing each module's build/test status, skipped modules, integration test result, and total duration

**Checkpoint**: This is the MVP. Open a test PR touching one service. All four pipeline stages must fire and report correctly in the PR Checks tab before proceeding to Phase 4.

---

## Phase 4: User Story 2 - On-Demand Test Suite Execution (Priority: P2)

**Goal**: Any team member can trigger a build and test run from the GitHub Actions UI, targeting any branch, with optional module scope selection.

**Independent Test**: Navigate to Actions → On-Demand Pipeline → Run workflow. Set `modules: svc-audit`, `ref: main`. Confirm only `svc-audit` runs in build/unit-test matrices. Confirm integration tests run. Confirm job summary is posted.

### Implementation for User Story 2

- [x] T007 [US2] Implement `.github/workflows/on-demand.yml` as an orchestrating workflow with: `on: workflow_dispatch` trigger with inputs `modules` (string, description `"Comma-separated module names or 'all'"`, default `"all"`) and `ref` (string, description `"Branch name or SHA to test"`, default `"${{ github.ref_name }}"`); `detect` job calling `./.github/workflows/detect-changes.yml` passing `modules: ${{ inputs.modules }}`; `build` matrix job calling `./.github/workflows/build-module.yml` with `module: ${{ matrix.module }}` and `ref: ${{ inputs.ref }}`; `unit-test` matrix job calling `./.github/workflows/test-unit.yml` with `module` and `ref`; `integration` job calling `./.github/workflows/test-integration.yml` with `ref: ${{ inputs.ref }}` gated on `run_it_tests == 'true'`; inline `report` job posting a markdown job summary to `$GITHUB_STEP_SUMMARY` with per-module results and a totals row

**Checkpoint**: Manual workflow dispatch from the GitHub UI must work against at least two different branches and with both `all` and a single-module input.

---

## Phase 5: User Story 3 - Composable Pipeline Chaining (Priority: P3)

**Goal**: Each reusable workflow in the suite can be called independently or composed in any order. New custom pipelines can be assembled with minimal effort.

**Independent Test**: Author a new throwaway workflow file that calls `build-module.yml` directly (without calling `detect-changes.yml` first) and confirm it runs successfully for one module.

### Implementation for User Story 3

- [x] T008 [P] [US3] Create `.github/workflows/README.md` documenting: the purpose of each of the six workflow files; the full input/output contract for each reusable workflow (mirroring `specs/001-smart-ci-pipeline/contracts/workflow-contracts.md`); a worked example showing how to compose a custom pipeline that chains only `detect-changes.yml` and `build-module.yml`; a note that each reusable workflow can be called without the others and lists what each one requires from the caller
- [x] T009 [P] [US3] Verify independent callability: author a temporary workflow `.github/workflows/scratch-chain-test.yml` that calls only `./.github/workflows/build-module.yml` with `module: svc-declare` and no prior detect step; trigger it manually; confirm it builds successfully; then delete the scratch file

**Checkpoint**: A new workflow can be assembled from existing reusable workflows without modifying any existing files.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Validate end-to-end scenarios from `quickstart.md` and ensure operational robustness.

- [ ] T010 [P] Validate Quickstart Scenario 1 — open a PR modifying one file in `svc-declare/` and confirm: `detect` outputs `["svc-declare"]`, only one matrix job fires for build and unit-test, integration tests run, other services are absent from all matrix jobs, PR Checks tab shows green
- [ ] T011 [P] Validate Quickstart Scenario 3 — open a PR modifying `README.md` at repo root and confirm: `detect` sets `run_all: true`, all four service modules appear in build and unit-test matrices, integration tests run
- [ ] T012 [P] Validate Quickstart Scenario 6 — introduce a deliberate compilation error in `svc-declare` on a test branch, open a PR touching both `svc-declare` and `svc-audit`, and confirm: `svc-declare` build job fails, `svc-audit` build job still runs, PR is marked failed with per-module status visible in the summary

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Requires Phase 1 — blocks all user stories
- **User Stories (Phase 3, 4, 5)**: All require Phase 2 completion
  - US1 (Phase 3) must be complete before US2 (Phase 4) — `on-demand.yml` calls the same reusable workflows as `pr-pipeline.yml`
  - US3 (Phase 5) can begin after US1 is complete and does not block US2
- **Polish (Phase 6)**: Requires all user stories complete

### User Story Dependencies

- **US1 (P1)**: Requires only Phase 2 (detect-changes.yml)
- **US2 (P2)**: Requires US1 complete (reuses build-module.yml, test-unit.yml, test-integration.yml)
- **US3 (P3)**: Requires US1 complete (documents and validates the reusable workflows authored in US1)

### Within User Story 1

- T003, T004, T005 can be authored in parallel (separate files, no runtime dependency on each other)
- T006 (`pr-pipeline.yml`) requires T003, T004, T005, and T002 to be complete before it can be tested end-to-end

### Parallel Opportunities

All Phase 3 implementation tasks (T003, T004, T005) can be worked in parallel — they are separate YAML files with no authoring dependency on each other.

---

## Parallel Example: User Story 1

```bash
# These three workflow files can be authored simultaneously:
T003: .github/workflows/build-module.yml
T004: .github/workflows/test-unit.yml
T005: .github/workflows/test-integration.yml

# T006 (pr-pipeline.yml) is authored after all three above are tested standalone.
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001)
2. Complete Phase 2: Foundational (T002) — validate detect-changes.yml standalone
3. Complete Phase 3: US1 (T003–T006)
4. **STOP and VALIDATE**: Open a test PR. Confirm selective builds fire and PR Checks report correctly.
5. Demo to stakeholders before proceeding to Phase 4.

### Incremental Delivery

1. Setup + Foundational → detect-changes.yml is live and testable
2. US1 → PR pipeline is live; every PR now gets selective CI (**MVP shipped**)
3. US2 → on-demand pipeline is live; team can run tests on any branch at any time
4. US3 → composability documented and verified; new pipelines can be added without coaching
5. Polish → operational confidence validated against all quickstart scenarios

### Parallel Team Strategy

With two developers after Phase 2:
- Developer A: T003, T004, T005, T006 (US1 — PR pipeline)
- Developer B: Can begin T008 (README documentation) for US3 in parallel

T007 (US2 — on-demand) should wait until T003–T005 are stable, since it calls the same reusable workflows.

---

## Notes

- [P] tasks operate on different files with no dependency on incomplete tasks — safe to run in parallel
- [Story] label maps each task to its user story for traceability against spec.md
- Each user story phase ends with an explicit checkpoint — stop and validate before moving on
- The detect-changes.yml (T002) is the single most critical task: if its outputs are incorrect, all downstream workflows behave incorrectly
- Reusable workflows referenced via `./.github/workflows/<name>.yml` (local path) require the caller and the callee to be on the same ref — this is the correct GitHub Actions pattern for same-repo reusable workflows
- `docker compose --wait` (not `--wait-for`) requires Docker Compose v2.1.1+; verify the ubuntu-latest runner has a compatible version before implementing T005
