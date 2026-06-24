# Implementation Plan: GHCR Image Publishing

**Branch**: `003-ghcr-image-publishing` | **Date**: 2026-06-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-ghcr-image-publishing/spec.md`

## Summary

Extend the CI pipeline so that every successful service module build automatically publishes a JVM-mode Docker image to GitHub Container Registry (GHCR), tagged with the commit SHA, branch name, and (for `main` builds) `latest`. This gives operators a stable, indexed set of prior builds they can pull and deploy without re-running the pipeline.

The change is confined to GitHub Actions workflow files. No application source code is modified.

## Technical Context

**Language/Version**: GitHub Actions YAML; Docker (BuildKit); Java 21 / Maven 3.9 (existing)

**Primary Dependencies**:
- `docker/login-action` — GHCR authentication
- `docker/metadata-action` — tag generation from git context
- `docker/build-push-action` — multi-tag build + push with layer caching
- `GITHUB_TOKEN` — automatic credential for `packages: write`

**Storage**: GitHub Container Registry (`ghcr.io/nichom01/neversoft/<module>`)

**Testing**: Manual validation via GitHub Actions run logs, GHCR packages page, and `docker pull` (see [quickstart.md](quickstart.md))

**Target Platform**: GitHub Actions (`ubuntu-latest`); images are JVM-mode Linux containers

**Project Type**: CI/CD pipeline extension (workflow-only change)

**Performance Goals**: Image build and push completes within 5 minutes of the Maven build step finishing (JVM Dockerfile builds take ~3–5 min in CI)

**Constraints**: Must not block PR merges on transient GHCR outages; `packages: write` permission must be scoped only to the publish job

**Scale/Scope**: 4 publishable modules (`svc-declare`, `svc-audit`, `svc-validate`, `svc-risk`); one image per module per successful build

## Constitution Check

*No project constitution is in force (constitution.md is an unfilled template). No gates to evaluate.*

## Project Structure

### Documentation (this feature)

```text
specs/003-ghcr-image-publishing/
├── plan.md              # This file
├── research.md          # Phase 0 — all decisions resolved
├── data-model.md        # Entity definitions and tag rules
├── quickstart.md        # Validation scenarios
├── contracts/
│   └── image-reference.md   # Stable pull-reference contract
└── tasks.md             # Phase 2 output (/speckit-tasks — not yet created)
```

### Source Code (repository root)

Only workflow files change. No application source is modified.

```text
.github/workflows/
├── build-module.yml         # MODIFIED — add docker-publish job after build
└── pr-pipeline.yml          # MODIFIED — grant packages: write permission
```

**Structure Decision**: Purely a CI pipeline change. The `docker-publish` job is added as a dependent job inside the existing `build-module.yml` reusable workflow so the publish step is co-located with the build it depends on and is automatically skipped for `lib-consumer-map` (which is never called as a buildable module).

## Implementation Notes

### `build-module.yml` changes

Add a `docker-publish` job that:
1. `needs: build` — only runs if the Maven build job succeeded
2. Has `permissions: packages: write` scoped to this job only
3. Steps:
   - `docker/login-action` with `registry: ghcr.io`, username `${{ github.actor }}`, password `${{ secrets.GITHUB_TOKEN }}`
   - `docker/metadata-action` generating tags:
     - `sha-{{ sha | truncate(7) }}`
     - `{{ branch | sanitise }}`
     - `latest` (only when `github.ref == 'refs/heads/main'`)
   - `docker/build-push-action` with:
     - `context: .` (repo root — required for multi-module Dockerfiles like svc-audit)
     - `file: ${{ inputs.module }}/Dockerfile.jvm`
     - `push: true`
     - `tags: ${{ steps.meta.outputs.tags }}`
     - `cache-from: type=gha` / `cache-to: type=gha,mode=max`
4. A `continue-on-error: true` guard on the push step (GHCR outage must not fail the workflow)
5. Outputs: `image_published` and `image_ref` (SHA-tagged reference)

### `pr-pipeline.yml` changes

Add `packages: write` to the top-level `permissions` block (or the build job's permissions, whichever is least-privilege). GitHub Actions requires this for GHCR pushes from reusable workflows.

### Module → Dockerfile mapping

| Module | Dockerfile | Build context |
|--------|-----------|---------------|
| `svc-declare` | `svc-declare/Dockerfile.jvm` | `.` (repo root) |
| `svc-audit` | `svc-audit/Dockerfile.jvm` | `.` (repo root) |
| `svc-validate` | `svc-validate/Dockerfile.jvm` | `.` (repo root) |
| `svc-risk` | `svc-risk/Dockerfile.jvm` | `.` (repo root) |

All use repo root as build context because the JVM Dockerfiles for svc-audit, svc-validate, and svc-risk use `COPY lib-consumer-map/` instructions that require access to sibling directories.
