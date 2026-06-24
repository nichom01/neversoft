# Tasks: GHCR Image Publishing

**Feature**: specs/003-ghcr-image-publishing
**Plan**: [plan.md](plan.md)
**Spec**: [spec.md](spec.md)

## Phase 1 — Grant pipeline permission to push to GHCR

### Task 1.1 — Add `packages: write` permission to pr-pipeline.yml

- [x] **File**: `.github/workflows/pr-pipeline.yml`
- **What**: Add `packages: write` to the top-level `permissions` block so reusable workflow jobs can push to GHCR
- **Why**: GitHub Actions requires this permission to be declared in the calling workflow for GHCR pushes from reusable workflows

---

## Phase 2 — Add docker-publish job to build-module.yml

### Task 2.1 — Add `docker-publish` job after the `build` job

- [x] **File**: `.github/workflows/build-module.yml`
- **What**: Add a new `docker-publish` job that depends on `build`, authenticates to GHCR, generates image tags, and builds + pushes the JVM-mode Docker image
- **Details**:
  - `needs: build` + `if: needs.build.outputs.status == 'passed'`
  - `permissions: packages: write`
  - Steps: login → metadata → build-push (with `continue-on-error: true` on push)
  - Tags: `sha-<7-char-sha>`, sanitised branch name, `latest` (main only)
  - Build context: `.` (repo root); Dockerfile: `${{ inputs.module }}/Dockerfile.jvm`
  - GHA cache: `type=gha`
- **Why**: This is the core of the feature — publish an image on every successful build

### Task 2.2 — Expose image publish outputs from build-module.yml

- [x] **File**: `.github/workflows/build-module.yml`
- **What**: Add `image_published` and `image_ref` to the workflow's `outputs` block and wire them from the `docker-publish` job
- **Why**: Downstream jobs (e.g., the pipeline report) can surface whether publish succeeded

### Task 2.3 — Update pipeline summary to show image publish status

- [x] **File**: `.github/workflows/pr-pipeline.yml`
- **What**: Add image publish status to the `report` job's step summary table
- **Why**: Operators can see at a glance whether images were published without digging into job logs

---

## Phase 3 — Validation

### Task 3.1 — Verify workflows parse correctly

- [x] **What**: Run `yamllint` (or equivalent) on both modified workflow files to confirm valid YAML
- **Why**: Invalid YAML silently breaks GitHub Actions

### Task 3.2 — Manual smoke test (post-push)

- [x] **What**: Push the changes, trigger a CI run on a feature branch, and validate via the quickstart scenarios
- **Reference**: [quickstart.md](quickstart.md)
- **Why**: Only a real CI run confirms GHCR authentication, tag application, and the `latest` guard work correctly
