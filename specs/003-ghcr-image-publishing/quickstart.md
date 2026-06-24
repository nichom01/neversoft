# Quickstart: Validating GHCR Image Publishing

## Prerequisites

- Access to the `nichom01/neversoft` GitHub repository (to view Actions and Packages)
- Docker installed locally (to pull and run images)
- Authenticated to GHCR: `docker login ghcr.io -u <github-username> --password-stdin <<< <PAT-or-GITHUB-TOKEN>`

---

## Scenario 1 — Verify automatic publish on successful build

**Goal**: Confirm that pushing a change to any service module triggers an image build and GHCR push.

1. Push a change to any file in `svc-declare/` (e.g., add a comment) on a feature branch.
2. Open the GitHub Actions run for the push.
3. In the CI Pipeline run, expand the **Build** stage for `svc-declare`.
4. Confirm the **Write step summary** shows `image_published: true` and an image reference like `ghcr.io/nichom01/neversoft/svc-declare:sha-XXXXXXX`.
5. Navigate to **Packages** on the repo page and confirm `svc-declare` package lists the new SHA tag.

**Expected**: Image appears in GHCR within ~5 minutes of the build step completing.

---

## Scenario 2 — Pull and run a specific prior build by SHA tag

**Goal**: Confirm an operator can retrieve and run a known prior image without triggering a new build.

1. From the GHCR packages page, note a SHA tag for `svc-declare` (e.g., `sha-a1b2c3d`).
2. Pull the image locally:
   ```
   docker pull ghcr.io/nichom01/neversoft/svc-declare:sha-a1b2c3d
   ```
3. Confirm the pull succeeds and the image ID matches what was published.
4. Run the image (infrastructure not required — just confirm it starts):
   ```
   docker run --rm ghcr.io/nichom01/neversoft/svc-declare:sha-a1b2c3d
   ```
   The service will fail to connect to its dependencies, but the container should start and log a startup attempt rather than a Docker error.

**Expected**: Pull succeeds without re-running CI; image runs as a container.

---

## Scenario 3 — Verify `latest` tag tracks main-branch builds only

**Goal**: Confirm the `latest` tag points to the most recent main-branch build and is not updated by feature-branch builds.

1. Note the current digest of `ghcr.io/nichom01/neversoft/svc-declare:latest`:
   ```
   docker manifest inspect ghcr.io/nichom01/neversoft/svc-declare:latest | grep digest
   ```
2. Push a change to `svc-declare/` on a feature branch and wait for the build to complete.
3. Re-check the `latest` digest — it should be unchanged.
4. Merge the feature branch to `main`. Wait for the main-branch build.
5. Re-check the `latest` digest — it should now point to the new build.

**Expected**: `latest` only advances when `main` branch builds successfully.

---

## Scenario 4 — Verify build failure produces no new image

**Goal**: Confirm a failed build does not result in a published image.

1. Introduce a compile error in `svc-declare/src/` on a branch.
2. Push and wait for the CI run to fail at the Maven build step.
3. Navigate to the GHCR package for `svc-declare`.
4. Confirm no new tag corresponding to the failing commit SHA appears.

**Expected**: No new image tag published for the failing commit.

---

## Scenario 5 — Verify library module is excluded

**Goal**: Confirm `lib-consumer-map` does not appear as a GHCR package.

1. Push a change to `lib-consumer-map/` on any branch.
2. Check the GHCR packages page for the repository.
3. Confirm no `lib-consumer-map` package exists.

**Expected**: `lib-consumer-map` never appears in GHCR packages.

---

## Reference

- Image naming contract: [contracts/image-reference.md](contracts/image-reference.md)
- Tagging rules and entity definitions: [data-model.md](data-model.md)
