# Workflow Contracts: Smart CI Pipeline

Each reusable workflow in `.github/workflows/` is a callable unit. This document defines the contract (inputs, outputs, triggers) for each one. Workflows reference each other by path; the orchestrating workflows call the reusable ones.

---

## Reusable Workflows

### `detect-changes.yml`

**Purpose**: Determine which modules are affected by the current change set.

**Trigger**: `workflow_call`

**Inputs**:

| Input         | Type    | Required | Default | Description                                                    |
|---------------|---------|----------|---------|----------------------------------------------------------------|
| `modules`     | string  | No       | `"all"` | Comma-separated module names, or `all` to force full run       |
| `base_ref`    | string  | No       | (from PR event or default branch) | The ref to diff against              |

**Outputs**:

| Output              | Type    | Description                                              |
|---------------------|---------|----------------------------------------------------------|
| `affected_modules`  | string  | JSON array of module names, e.g. `["declare-svc"]`      |
| `run_all`           | string  | `"true"` if root-level files changed                    |
| `run_it_tests`      | string  | `"true"` if IT tests should be triggered                |
| `detection_source`  | string  | `detected` \| `manual` \| `full-fallback`               |

**Behaviour**:
- If `modules` input is `all` → set `run_all: true`, return full module list
- If `modules` input is a list → return that list as-is (`detection_source: manual`)
- Otherwise, run `git diff --name-only <base_ref>...HEAD` and filter paths by module directory prefixes
- If git diff fails → set `detection_source: full-fallback`, return full module list
- Set `run_it_tests: true` if any of `{declare-svc, audit-svc, validate-svc, risk-svc, infra, it-tests}` appear in affected modules

---

### `build-module.yml`

**Purpose**: Build a single Maven module, skipping tests.

**Trigger**: `workflow_call`

**Inputs**:

| Input    | Type   | Required | Default | Description                             |
|----------|--------|----------|---------|-----------------------------------------|
| `module` | string | Yes      | —       | Module directory name (e.g. `audit-svc`) |
| `ref`    | string | No       | (triggering SHA) | Git ref to check out          |

**Outputs**:

| Output       | Type   | Description                    |
|--------------|--------|--------------------------------|
| `status`     | string | `passed` \| `failed`          |
| `duration_s` | number | Wall-clock seconds for the job |

**Behaviour**:
- Check out `ref`
- Set up Java 21
- Run `mvn -f <module>/pom.xml package -DskipTests`
- Report status via job outputs

---

### `test-unit.yml`

**Purpose**: Run unit tests for a single Maven module using Maven Surefire.

**Trigger**: `workflow_call`

**Inputs**:

| Input    | Type   | Required | Default | Description                             |
|----------|--------|----------|---------|-----------------------------------------|
| `module` | string | Yes      | —       | Module directory name                   |
| `ref`    | string | No       | (triggering SHA) | Git ref to check out          |

**Outputs**:

| Output         | Type   | Description                         |
|----------------|--------|-------------------------------------|
| `status`       | string | `passed` \| `failed`               |
| `tests_run`    | number | Total tests executed                |
| `tests_failed` | number | Number of failed tests              |
| `duration_s`   | number | Wall-clock seconds for the job      |

**Behaviour**:
- Check out `ref`
- Set up Java 21
- Run `mvn -f <module>/pom.xml test`
- Parse Surefire XML reports for counts
- Report status and counts via job outputs

---

### `test-integration.yml`

**Purpose**: Run the full integration test suite from the `it-tests` module using Maven Failsafe.

**Trigger**: `workflow_call`

**Inputs**:

| Input  | Type   | Required | Default          | Description              |
|--------|--------|----------|------------------|--------------------------|
| `ref`  | string | No       | (triggering SHA) | Git ref to check out     |

**Outputs**:

| Output         | Type   | Description                         |
|----------------|--------|-------------------------------------|
| `status`       | string | `passed` \| `failed`               |
| `tests_run`    | number | Total ITs executed                  |
| `tests_failed` | number | Number of failed ITs                |
| `duration_s`   | number | Wall-clock seconds for the job      |

**Behaviour**:
- Check out `ref`
- Set up Java 21
- Start environment: `docker compose -f infra/docker-compose.yml up -d`
- Wait for services to be healthy (readiness check via Docker health status)
- Run `mvn -f it-tests/pom.xml verify -Dsurefire.skip=true`
- Tear down environment: `docker compose -f infra/docker-compose.yml down`
- Report status via job outputs

---

## Orchestrating Workflows

### `pr-pipeline.yml`

**Purpose**: Entry point for pull request events. Chains detect → build (matrix) → unit-test (matrix) → integration-test (conditional) → report.

**Trigger**: `pull_request` (types: opened, synchronize, reopened)

**Jobs**:

```
detect        → calls detect-changes.yml
build         → matrix over affected_modules, calls build-module.yml (needs: detect)
unit-test     → matrix over affected_modules, calls test-unit.yml   (needs: build)
integration   → calls test-integration.yml if run_it_tests == 'true' (needs: unit-test)
report        → posts summary to PR                                  (needs: [unit-test, integration])
```

**No inputs** (driven entirely by PR event context).

---

### `on-demand.yml`

**Purpose**: Manual trigger for running builds and tests against any branch.

**Trigger**: `workflow_dispatch`

**Inputs**:

| Input     | Type   | Required | Default        | Description                                                  |
|-----------|--------|----------|----------------|--------------------------------------------------------------|
| `modules` | string | No       | `all`          | `all` or comma-separated module names                        |
| `ref`     | string | No       | default branch | Branch name or SHA to test                                   |

**Jobs**:

```
detect        → calls detect-changes.yml (passes modules input)
build         → matrix over affected_modules, calls build-module.yml (needs: detect)
unit-test     → matrix over affected_modules, calls test-unit.yml   (needs: build)
integration   → calls test-integration.yml if run_it_tests == 'true' (needs: unit-test)
report        → posts run summary as job summary                     (needs: [unit-test, integration])
```

---

## Dependency Graph

```
pr-pipeline.yml
│
├── detect-changes.yml          (outputs: affected_modules, run_it_tests)
│
├── build-module.yml ×N         (matrix: affected_modules)
│     └── needs: detect
│
├── test-unit.yml ×N            (matrix: affected_modules)
│     └── needs: build
│
├── test-integration.yml        (conditional: run_it_tests == 'true')
│     └── needs: unit-test
│
└── report job                  (inline, summarises all results)
      └── needs: [unit-test, integration]
```

The same graph applies to `on-demand.yml` with the addition of user-provided `modules` and `ref` inputs flowing into `detect-changes.yml`.
