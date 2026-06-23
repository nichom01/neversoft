# GitHub Actions Workflow Suite

Composable CI pipeline for the Neversoft monorepo. Each reusable workflow is a self-contained unit that can be called independently or chained in any order.

## Workflow Files

| File | Type | Trigger | Purpose |
|------|------|---------|---------|
| `detect-changes.yml` | Reusable | `workflow_call` | Determines which modules are affected |
| `build-module.yml` | Reusable | `workflow_call` | Builds a single Maven module |
| `test-unit.yml` | Reusable | `workflow_call` | Runs Surefire unit tests for one module |
| `test-integration.yml` | Reusable | `workflow_call` | Runs the full Failsafe IT suite |
| `pr-pipeline.yml` | Orchestrator | `pull_request` | Selective CI on every PR |
| `on-demand.yml` | Orchestrator | `workflow_dispatch` | Manual runs against any branch |

---

## Reusable Workflow Contracts

### `detect-changes.yml`

**Inputs**

| Input | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `modules` | string | No | `''` | Comma-separated names, `'all'`, or empty for auto-detect |
| `base_ref` | string | No | `''` | SHA/branch to diff against (required for auto-detect) |

**Outputs**

| Output | Type | Description |
|--------|------|-------------|
| `affected_modules` | string | JSON array of buildable modules e.g. `["svc-declare"]` |
| `has_buildable_changes` | string | `"true"` if `affected_modules` is non-empty |
| `run_it_tests` | string | `"true"` if IT tests should run |
| `run_all` | string | `"true"` if all modules are included |
| `detection_source` | string | `detected` \| `manual` \| `full-fallback` |

---

### `build-module.yml`

**Inputs**

| Input | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `module` | string | Yes | — | Module directory name (e.g. `svc-declare`) |
| `ref` | string | No | `''` | Git ref to check out |

**Outputs**

| Output | Type | Description |
|--------|------|-------------|
| `status` | string | `passed` \| `failed` |
| `duration_s` | number | Wall-clock seconds |

---

### `test-unit.yml`

**Inputs**

| Input | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `module` | string | Yes | — | Module directory name |
| `ref` | string | No | `''` | Git ref to check out |

**Outputs**

| Output | Type | Description |
|--------|------|-------------|
| `status` | string | `passed` \| `failed` |
| `tests_run` | number | Total tests executed |
| `tests_failed` | number | Failed + errored tests |
| `duration_s` | number | Wall-clock seconds |

---

### `test-integration.yml`

**Inputs**

| Input | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `ref` | string | No | `''` | Git ref to check out |

**Outputs**

| Output | Type | Description |
|--------|------|-------------|
| `status` | string | `passed` \| `failed` |
| `tests_run` | number | Total ITs executed |
| `tests_failed` | number | Failed + errored ITs |
| `duration_s` | number | Wall-clock seconds |

---

## Composing a Custom Pipeline

Any workflow file in this directory can call the reusable workflows directly. Each reusable workflow can be called without the others — there are no hidden cross-workflow dependencies.

### What each reusable workflow requires from the caller

| Workflow | Caller must provide |
|----------|---------------------|
| `detect-changes.yml` | Nothing (inputs optional) |
| `build-module.yml` | `module` input (required) |
| `test-unit.yml` | `module` input (required) |
| `test-integration.yml` | Nothing (inputs optional) |

### Example: Build-only pipeline for a specific module

```yaml
name: Build Only

on:
  workflow_dispatch:
    inputs:
      module:
        description: "Module to build"
        type: string
        default: "svc-declare"

jobs:
  build:
    uses: ./.github/workflows/build-module.yml
    with:
      module: ${{ inputs.module }}
```

### Example: Detect + Build chain (no unit tests)

```yaml
name: Detect and Build

on:
  push:
    branches: [main]

jobs:
  detect:
    uses: ./.github/workflows/detect-changes.yml
    with:
      base_ref: ${{ github.event.before }}

  build:
    needs: detect
    if: needs.detect.outputs.has_buildable_changes == 'true'
    strategy:
      matrix:
        module: ${{ fromJson(needs.detect.outputs.affected_modules) }}
    uses: ./.github/workflows/build-module.yml
    with:
      module: ${{ matrix.module }}
```

### Example: Call integration tests directly (no build or detect step needed)

```yaml
name: Integration Tests Only

on:
  schedule:
    - cron: '0 3 * * *'  # nightly at 03:00 UTC

jobs:
  integration:
    uses: ./.github/workflows/test-integration.yml
    with:
      ref: main
```

---

## Known Modules

| Module | Type | Unit tests | Triggers IT tests |
|--------|------|-----------|-------------------|
| `svc-declare` | Quarkus service | Yes | Yes |
| `svc-audit` | Quarkus service | Yes | Yes |
| `svc-validate` | Quarkus service (JVM) | Yes | Yes |
| `svc-risk` | Quarkus service | Yes | Yes |
| `integration-tests` | Failsafe suite | No | Yes (is the IT suite) |
| `infra` | Docker Compose | No | Yes |

To add a new service module: add its directory name to the `BUILDABLE_MODULES` array in `detect-changes.yml`.
