# Quickstart & Validation Guide: Smart CI Pipeline

This guide describes how to validate that the pipeline works correctly after implementation. It covers the primary flows and how to confirm each one behaves as specified.

---

## Prerequisites

- A GitHub repository with Actions enabled
- The workflows created under `.github/workflows/`
- Java 21 available on the GitHub-hosted runner (or configured via `setup-java`)
- Docker available on the runner for integration tests (GitHub-hosted `ubuntu-latest` includes Docker)
- The `infra/docker-compose.yml` correctly brings up the required backing services

---

## Scenario 1: Selective PR Build (single module change)

**What to validate**: Only the changed module is built and tested; others are skipped.

**Steps**:
1. Create a feature branch from `main`
2. Modify a file inside `declare-svc/` (e.g., add a comment to any `.java` file)
3. Open a pull request targeting `main`
4. Observe the `pr-pipeline` workflow triggered under "Actions" in the PR

**Expected outcomes**:
- `detect-changes` job completes and outputs `affected_modules: ["declare-svc"]`
- `build` matrix runs **one** job: `declare-svc`
- `unit-test` matrix runs **one** job: `declare-svc`
- `integration` job is triggered (because `declare-svc` is a service module that affects IT tests)
- All other services (`audit-svc`, `validate-svc`, `risk-svc`) are **not** present as matrix jobs
- PR shows all checks green

**Verification**: In the Actions run, expand the `detect` job step output and confirm `affected_modules` contains only `["declare-svc"]`.

---

## Scenario 2: Multi-Module PR

**What to validate**: Multiple changed modules each get their own build/test job in parallel.

**Steps**:
1. Create a feature branch from `main`
2. Modify files in both `declare-svc/` and `audit-svc/`
3. Open a pull request targeting `main`

**Expected outcomes**:
- `detect-changes` outputs `affected_modules: ["declare-svc", "audit-svc"]` (order may vary)
- `build` matrix runs **two** jobs in parallel
- `unit-test` matrix runs **two** jobs in parallel
- `integration` job runs after both unit-test jobs complete

**Verification**: In the Actions run timeline view, confirm both build jobs start at approximately the same time (parallel execution).

---

## Scenario 3: Root-Level File Change (Full Pipeline)

**What to validate**: Changes to root-level or shared files trigger a full pipeline run.

**Steps**:
1. Create a branch and modify `README.md` or `CLAUDE.md` at the repo root
2. Open a pull request

**Expected outcomes**:
- `detect-changes` sets `run_all: true`
- All four services appear in the `affected_modules` output
- All four service build and unit-test jobs run
- Integration tests run

---

## Scenario 4: On-Demand Single Module Test

**What to validate**: A developer can trigger a test run for a specific module against any branch.

**Steps**:
1. Go to the repository's **Actions** tab in GitHub
2. Select the **On-Demand Pipeline** workflow
3. Click **Run workflow**
4. Set `modules` to `audit-svc` and `ref` to `main`
5. Click **Run workflow**

**Expected outcomes**:
- Only `audit-svc` appears in build and unit-test matrix jobs
- Integration tests run (because `audit-svc` is a service module)
- Run summary is posted as a job summary (visible in the workflow run)

---

## Scenario 5: On-Demand Full Suite

**What to validate**: Running `all` modules works correctly.

**Steps**:
1. Go to **Actions** → **On-Demand Pipeline** → **Run workflow**
2. Leave `modules` as `all`, set `ref` to any branch
3. Run

**Expected outcomes**:
- All four service modules appear in build and unit-test matrices
- Integration tests run
- All jobs complete with a summary

---

## Scenario 6: Failed Module Does Not Block Others

**What to validate**: A build or test failure in one module does not prevent other modules from running.

**Steps**:
1. Introduce a deliberate compilation error in `declare-svc` (e.g., remove a semicolon)
2. Open a PR that changes both `declare-svc` and `audit-svc`

**Expected outcomes**:
- `declare-svc` build job **fails**
- `audit-svc` build job **still runs** and may pass
- `unit-test` jobs for passing modules still run
- PR is marked as failed with a clear indication of which module failed

**Note**: Integration tests are blocked if any upstream module fails (since a failing service cannot be meaningfully integration-tested).

---

## Scenario 7: Change Detection Fallback

**What to validate**: If git diff fails, the pipeline falls back to a full run safely.

**Steps** (simulated in a test run):
1. Trigger the pipeline in a context where `base_ref` is unavailable (e.g., a shallow clone with insufficient history)
2. Observe `detect-changes` behaviour

**Expected outcomes**:
- `detection_source` output is `full-fallback`
- All modules are treated as affected
- Pipeline completes a full run rather than silently skipping modules

---

## Adding a New Module

When a new service directory (e.g., `notify-svc`) is added:

1. Create `notify-svc/pom.xml` following the same Maven structure as existing services
2. Add `notify-svc` to the known module list in `.github/workflows/detect-changes.yml`
3. Open a PR — the PR touching `notify-svc/` will now correctly trigger build and test for it

**Verification**: The `detect-changes` output includes `notify-svc` in `affected_modules` when files in `notify-svc/` are changed.

---

## Where to Find Results

| Result                    | Location                                                     |
|---------------------------|--------------------------------------------------------------|
| Per-module build status   | GitHub Actions → individual matrix job logs                  |
| Per-module test results   | GitHub Actions → unit-test matrix job → Surefire report step |
| Integration test results  | GitHub Actions → integration job → Failsafe report step      |
| Overall pipeline summary  | GitHub Actions → run summary (posted by `report` job)        |
| PR check status           | Pull request → "Checks" tab                                  |
