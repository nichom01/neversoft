# Data Model: Smart CI Pipeline

This document defines the core concepts and their relationships within the pipeline system. These are logical entities that manifest as workflow inputs, outputs, and job parameters — not data stored in a database.

---

## Module

A discrete service or test module in the repository.

| Field         | Type     | Description                                              |
|---------------|----------|----------------------------------------------------------|
| `name`        | string   | Directory name (e.g., `declare-svc`, `audit-svc`)       |
| `path`        | string   | Relative path from repo root (same as name for top-level modules) |
| `type`        | enum     | `service` \| `integration-tests` \| `infra`             |
| `build_cmd`   | string   | Maven command to build this module                       |
| `test_cmd`    | string   | Maven command to run unit tests for this module          |

**Known modules at time of writing**:

| Name          | Type                | Triggers IT tests when changed |
|---------------|---------------------|-------------------------------|
| `declare-svc` | service             | Yes                           |
| `audit-svc`   | service             | Yes                           |
| `validate-svc`| service             | Yes                           |
| `risk-svc`    | service             | Yes                           |
| `it-tests`    | integration-tests   | Yes (always)                  |
| `infra`       | infra               | Yes                           |

---

## Change Set

The set of files identified as changed in a pipeline trigger event.

| Field           | Type       | Description                                          |
|-----------------|------------|------------------------------------------------------|
| `trigger`       | enum       | `pull_request` \| `manual`                           |
| `base_ref`      | string     | Branch or SHA being compared against                 |
| `head_sha`      | string     | The commit SHA being evaluated                       |
| `changed_files` | string[]   | List of relative file paths that changed             |

---

## Affected Modules

Derived from Change Set by matching changed file paths against known module directory prefixes.

| Field              | Type     | Description                                              |
|--------------------|----------|----------------------------------------------------------|
| `modules`          | string[] | Names of modules with at least one changed file          |
| `run_all`          | boolean  | `true` if root-level or shared files changed (forces full run) |
| `run_it_tests`     | boolean  | `true` if any service, infra, or it-tests module is affected |
| `source`           | enum     | `detected` \| `manual` \| `full-fallback`               |

**`source` values**:
- `detected` — derived from git diff (normal PR flow)
- `manual` — provided explicitly by user at workflow dispatch time
- `full-fallback` — change detection failed; all modules treated as affected

---

## Pipeline Run

A single execution of a chain of pipeline actions.

| Field           | Type     | Description                                              |
|-----------------|----------|----------------------------------------------------------|
| `run_id`        | string   | GitHub Actions run ID                                    |
| `trigger`       | enum     | `pull_request` \| `manual`                              |
| `affected`      | Affected Modules | The resolved scope for this run                  |
| `module_results`| ModuleResult[] | One result per module that ran                   |
| `it_result`     | TestResult \| null | Integration test result (null if not triggered) |
| `overall_status`| enum     | `passed` \| `failed` \| `skipped`                      |

---

## ModuleResult

The outcome of building and unit-testing a single module.

| Field         | Type   | Description                                      |
|---------------|--------|--------------------------------------------------|
| `module`      | string | Module name                                      |
| `build_status`| enum   | `passed` \| `failed` \| `skipped`               |
| `test_status` | enum   | `passed` \| `failed` \| `skipped`               |
| `duration_s`  | number | Wall-clock seconds for build + test combined     |
| `skipped`     | boolean| `true` if module was not in affected set         |

---

## Workflow Inputs/Outputs Schema

Each reusable workflow exposes typed inputs and outputs. These are the "interface contracts" of the pipeline system.

### `detect-changes` outputs

```
affected_modules: JSON array string — e.g., '["declare-svc","audit-svc"]'
run_all:          boolean string    — 'true' or 'false'
run_it_tests:     boolean string    — 'true' or 'false'
detection_source: string            — 'detected' | 'manual' | 'full-fallback'
```

### `build-module` inputs / outputs

```
inputs:
  module:  string (required) — module directory name
  ref:     string (optional) — git ref to check out (default: triggering SHA)

outputs:
  status:     string — 'passed' | 'failed'
  duration_s: number
```

### `test-unit` inputs / outputs

```
inputs:
  module: string (required) — module directory name
  ref:    string (optional)

outputs:
  status:       string — 'passed' | 'failed' | 'skipped'
  tests_run:    number
  tests_failed: number
  duration_s:   number
```

### `test-integration` inputs / outputs

```
inputs:
  ref: string (optional)

outputs:
  status:       string — 'passed' | 'failed'
  tests_run:    number
  tests_failed: number
  duration_s:   number
```

### `on-demand` inputs

```
inputs:
  modules: string (optional, default 'all') — 'all' or comma-separated list
  ref:     string (optional, default: default branch) — branch or SHA to test
```
