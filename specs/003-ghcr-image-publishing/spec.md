# Feature Specification: GHCR Image Publishing

**Feature Branch**: `003-ghcr-image-publishing`

**Created**: 2026-06-24

**Status**: Draft

**Input**: User description: "when a component is built i would like to store it in the repo (ghcr) so if required i can get the latest or prior build to deploy"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Automatic Image Publish on Successful Build (Priority: P1)

When the CI pipeline successfully builds a component, the resulting image is automatically published to the GitHub Container Registry (GHCR) so that it is available for deployment without requiring a rebuild.

**Why this priority**: This is the foundation of the feature — no images in GHCR means no ability to retrieve prior builds. Everything else depends on this working correctly.

**Independent Test**: Can be fully tested by triggering a build of a single component and confirming a corresponding image appears in GHCR under the correct name with the correct tags.

**Acceptance Scenarios**:

1. **Given** a component build succeeds in CI, **When** the build pipeline completes, **Then** a new image is published to GHCR tagged with the commit SHA and the branch name.
2. **Given** a build on the `main` branch succeeds, **When** the pipeline completes, **Then** the image is additionally tagged as `latest`.
3. **Given** a build fails, **When** the pipeline terminates with a failure, **Then** no new image is published for that build.

---

### User Story 2 - Retrieve and Deploy a Prior Build (Priority: P2)

An operator needs to redeploy a previously published image — for example, to roll back to a known-good version — without re-running the build pipeline.

**Why this priority**: This is the core deployment use case the user described. Being able to pull and deploy a specific prior image is the primary value of having images in GHCR.

**Independent Test**: Can be fully tested by pulling an image by a specific prior commit SHA tag and running it, confirming the correct version is deployed without triggering a new build.

**Acceptance Scenarios**:

1. **Given** one or more images have been published to GHCR, **When** an operator specifies a particular commit SHA or version tag, **Then** that specific image can be pulled and deployed.
2. **Given** an operator wants the most recent successful build from `main`, **When** they pull the `latest` tag, **Then** they receive the image from the most recent successful main-branch build.
3. **Given** an operator specifies a tag that does not exist, **When** they attempt to pull it, **Then** they receive a clear error indicating the tag is not available.

---

### User Story 3 - Discover Available Published Images (Priority: P3)

A developer or operator can view which images are available in GHCR to identify prior builds suitable for deployment or comparison.

**Why this priority**: Without discoverability, operators must know a commit SHA upfront. Being able to browse available tags makes it practical to identify what to deploy.

**Independent Test**: Can be fully tested by navigating to the GHCR package page for a component and seeing a list of published tags.

**Acceptance Scenarios**:

1. **Given** multiple builds have been published, **When** an operator views the package in GHCR, **Then** they can see a list of available image tags with their associated commit information.

---

### Edge Cases

- What happens when GHCR is temporarily unavailable during a build — does the pipeline fail or continue without publishing?
- How does the system handle multiple components being built simultaneously — are their images published independently without collision?
- What happens if a component produces no meaningful build artifact (e.g., a library-only module with no runnable image)?
- How many prior image versions are retained before older ones are pruned?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The CI pipeline MUST publish a container image to GHCR for each component that successfully builds.
- **FR-002**: Each published image MUST be tagged with the full commit SHA of the triggering commit.
- **FR-003**: Each published image MUST be tagged with the source branch name.
- **FR-004**: Images produced from the `main` branch MUST additionally receive a `latest` tag.
- **FR-005**: The pipeline MUST NOT publish an image for a component whose build step failed.
- **FR-006**: Operators MUST be able to pull any previously published image by its commit SHA tag.
- **FR-007**: Images MUST be stored under the repository's GHCR namespace so they are accessible to users with repository access.
- **FR-008**: The publish step MUST be skipped for modules that do not produce a deployable container image (e.g., shared libraries).
- **FR-009**: Image names MUST clearly identify which component they represent so that images for different components do not conflict.

### Key Entities

- **Component Image**: A container image built from a single module in the repository. Identified by component name and one or more tags.
- **Image Tag**: A label on a published image. May represent a commit SHA, branch name, or the special `latest` pointer.
- **GHCR Package**: The registry entry under the repository namespace that holds all tags for a single component image.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every successful component build on any branch results in a published image in GHCR within 5 minutes of the build completing.
- **SC-002**: An operator can pull and run a specific prior build using only a commit SHA, with no additional pipeline execution required.
- **SC-003**: The `latest` tag always points to the most recent successful build from `main`, never to a build from a feature branch.
- **SC-004**: Images for different components are stored and retrievable independently — pulling one component's image does not require knowledge of another.
- **SC-005**: A build failure does not result in a new or updated image tag in GHCR.

## Assumptions

- GHCR is the target registry as specified by the user; no other registry is in scope.
- Repository access controls govern who can pull images; no additional access management is required as part of this feature.
- Only modules that produce runnable container images will be published; library modules (e.g., `lib-consumer-map`) are excluded.
- Image retention/pruning policy (e.g., keeping the last N images or N days) is out of scope for this feature and will be addressed separately if needed.
- The existing CI pipeline (GitHub Actions) is the publish trigger; no external build system is involved.
- Authentication between the CI pipeline and GHCR will use the standard `GITHUB_TOKEN` provided by GitHub Actions.
