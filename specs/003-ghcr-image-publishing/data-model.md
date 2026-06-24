# Data Model: GHCR Image Publishing

## Entities

### Component Image

A container image built from one of the four deployable service modules and stored in GHCR.

| Field | Description | Example |
|-------|-------------|---------|
| `registry` | Target registry host | `ghcr.io` |
| `owner` | GitHub organisation or user | `nichom01` |
| `repository` | GitHub repository name | `neversoft` |
| `module` | Source service module name | `svc-declare` |
| `full_name` | Fully-qualified image name | `ghcr.io/nichom01/neversoft/svc-declare` |

**Publishable modules**: `svc-declare`, `svc-audit`, `svc-validate`, `svc-risk`

**Excluded modules**: `lib-consumer-map` (library, no Dockerfile, not a standalone service)

---

### Image Tag

A label applied to a Component Image at publish time. Multiple tags may point to the same underlying image digest.

| Tag Type | Format | Example | Mutability |
|----------|--------|---------|------------|
| SHA tag | `sha-<7-char-git-sha>` | `sha-a1b2c3d` | Immutable |
| Branch tag | `<sanitised-branch-name>` | `main`, `feature-foo` | Mutable |
| Latest tag | `latest` | `latest` | Mutable — main branch only |

**Branch name sanitisation rule**: replace `/` with `-`, lowercase, strip characters not matching `[a-z0-9-]`. Applied to the branch name before using it as a tag.

**Latest tag rule**: applied only when `github.ref` equals `refs/heads/main`. Never applied to feature branch builds.

---

### Publish Event

A record of a single image publish operation, captured in the GitHub Actions step summary.

| Field | Description |
|-------|-------------|
| `module` | Which service was published |
| `tags` | List of tags applied |
| `digest` | Image content digest (SHA256) |
| `status` | `published` or `skipped` (GHCR unavailable) |
| `triggered_by` | Git SHA of the triggering commit |
| `branch` | Source branch name |

---

## Tag Resolution Rules

```
Given a successful build of module M at commit SHA C on branch B:

  SHA tag    = "sha-" + first7chars(C)
  Branch tag = sanitise(B)
  Latest tag = "latest"  (if B == "main", otherwise omitted)

Fully-qualified pull reference:
  ghcr.io/<owner>/<repo>/M:<tag>

Example (svc-declare, commit abc1234, branch main):
  ghcr.io/nichom01/neversoft/svc-declare:sha-abc1234
  ghcr.io/nichom01/neversoft/svc-declare:main
  ghcr.io/nichom01/neversoft/svc-declare:latest
```

---

## Dockerfile Source Mapping

| Module | Dockerfile used for GHCR publish | Build context |
|--------|----------------------------------|---------------|
| `svc-declare` | `svc-declare/Dockerfile.jvm` | repo root |
| `svc-audit` | `svc-audit/Dockerfile.jvm` | repo root |
| `svc-validate` | `svc-validate/Dockerfile.jvm` | repo root |
| `svc-risk` | `svc-risk/Dockerfile.jvm` | repo root |

All services use JVM-mode Dockerfiles for GHCR publishing. Native Dockerfiles are not used in this feature.
