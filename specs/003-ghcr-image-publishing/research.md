# Research: GHCR Image Publishing

## Decision 1: Where to add the Docker build + push step

**Decision**: Extend `build-module.yml` with a `docker-publish` job that runs after the Maven `build` job succeeds.

**Rationale**: The existing Maven build job already validates the code compiles cleanly. Triggering a Docker image build only after that succeeds satisfies FR-005 (no image published on failure). Adding a dependent `docker-publish` job in the same reusable workflow keeps the change self-contained and avoids touching `pr-pipeline.yml` beyond granting the required permission.

**Alternatives considered**:
- Create a separate `publish-image.yml` reusable workflow called from `pr-pipeline.yml` as Stage 2.5. Rejected: adds orchestration complexity for no benefit at this scope.
- Add Docker steps directly inside the existing `build` job. Rejected: mixes concerns (compile vs publish) and makes the job harder to retry independently.

---

## Decision 2: Which Dockerfile to use for published images

**Decision**: Use the JVM-mode Dockerfile (`Dockerfile.jvm`) for GHCR-published images.

**Rationale**: Native image builds (GraalVM) take 15–20 minutes per service in CI. JVM-mode builds take 3–5 minutes and produce images that run correctly. The primary value of this feature is enabling deployment of a known-good prior build — JVM images are fully deployable. Native image builds may be added in a future feature if production performance demands it.

**Alternatives considered**:
- Publish native images. Rejected for now due to build time cost on every branch push.
- Publish both and let operators choose. Rejected: adds complexity without an immediate need.

---

## Decision 3: Image naming convention

**Decision**: `ghcr.io/<github-owner>/<github-repo>/<module>` — all lowercase.

**Rationale**: GHCR requires lowercase image names. Using the GitHub owner and repo as a namespace ensures images are scoped to this repository's package registry automatically. The module name (e.g. `svc-declare`) uniquely identifies the component within the repo.

**Example**: `ghcr.io/nichom01/neversoft/svc-declare`

**Alternatives considered**:
- Single flat name with module as tag prefix (e.g., `ghcr.io/nichom01/neversoft:svc-declare-sha-abc`). Rejected: makes filtering by component harder and is non-standard.

---

## Decision 4: Tagging strategy

**Decision**: Apply three tags per successful build:
1. `sha-<7-char-SHA>` — e.g., `sha-a1b2c3d` — immutable, used for precise rollbacks
2. Sanitised branch name — e.g., `main`, `feature-foo` (slashes replaced with `-`) — mutable pointer to latest build on that branch
3. `latest` — mutable, applied only when the source branch is `main`

**Rationale**: The SHA tag is the anchor for rollbacks (FR-006). The branch tag lets operators quickly grab the latest build from a given branch. The `latest` tag satisfies FR-004 and SC-003.

**Alternatives considered**:
- Using the full 40-character SHA. Rejected: unnecessarily verbose; 7 characters gives sufficient collision resistance for this repo's volume.
- Semantic version tags (v1.2.3). Rejected: the repo does not currently version services independently.

---

## Decision 5: Authentication mechanism

**Decision**: Use the `GITHUB_TOKEN` secret provided automatically by GitHub Actions, with `packages: write` permission granted at the job level.

**Rationale**: No external credentials are required. GitHub Actions automatically provides `GITHUB_TOKEN`, and granting `packages: write` in the job's `permissions` block is the standard approach for GHCR pushes from the same repository.

**Alternatives considered**:
- Personal Access Token (PAT) stored as a secret. Rejected: PATs are tied to a user account and require manual rotation.

---

## Decision 6: Which modules publish images

**Decision**: Only the four buildable service modules publish images: `svc-declare`, `svc-audit`, `svc-validate`, `svc-risk`. `lib-consumer-map` is excluded.

**Rationale**: `lib-consumer-map` is a shared Java library with no Dockerfile and no standalone runtime. The four service modules each have `Dockerfile.jvm` files and represent independently deployable units. This satisfies FR-008.

---

## Decision 7: GHCR availability failure handling

**Decision**: Treat a GHCR push failure as a non-fatal warning — the pipeline reports the failure in the step summary but does not fail the overall workflow.

**Rationale**: A transient GHCR outage should not block a PR merge or break the main pipeline. The Maven build result (pass/fail) is the authoritative gate. If GHCR publish fails, the step summary notes it and operators can re-trigger manually.

**Alternatives considered**:
- Fail the workflow on GHCR push failure. Rejected: external registry availability should not gate code merges.

---

## Decision 8: Standard GitHub Actions Docker toolkit

**Decision**: Use the official GitHub Actions Docker actions:
- `docker/login-action` — authenticate to GHCR
- `docker/metadata-action` — generate tags from git context
- `docker/build-push-action` — build and push in one step with layer caching

**Rationale**: These are the de-facto standard for Docker in GitHub Actions, well-maintained by Docker Inc., and handle edge cases (multi-platform, cache, metadata) reliably.
