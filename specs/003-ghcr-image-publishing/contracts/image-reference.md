# Contract: GHCR Image Reference

## Purpose

Defines the stable contract operators use to pull images from GHCR. Any change to image naming or tagging is a breaking change and requires a migration plan.

---

## Image Name Pattern

```
ghcr.io/<owner>/<repo>/<module>:<tag>
```

| Segment | Value | Notes |
|---------|-------|-------|
| `<owner>` | `nichom01` | GitHub user/org — derived from `github.repository_owner` |
| `<repo>` | `neversoft` | GitHub repo name — derived from `github.event.repository.name` |
| `<module>` | `svc-declare` \| `svc-audit` \| `svc-validate` \| `svc-risk` | Always lowercase |
| `<tag>` | See tag contract below | |

---

## Tag Contract

### Pull by exact commit (rollback / reproducible deploy)

```
ghcr.io/nichom01/neversoft/<module>:sha-<7-char-sha>
```

- **Immutable** — once pushed, this tag is never overwritten
- `<7-char-sha>` is the first 7 characters of the triggering git commit SHA
- Available for every successful build on any branch
- Example: `ghcr.io/nichom01/neversoft/svc-declare:sha-a1b2c3d`

### Pull latest build from a specific branch

```
ghcr.io/nichom01/neversoft/<module>:<branch>
```

- **Mutable** — updated on every successful build for that branch
- Branch name is lowercased; `/` replaced with `-`; non-alphanumeric characters (except `-`) stripped
- Example (main branch): `ghcr.io/nichom01/neversoft/svc-declare:main`
- Example (feature branch `feat/foo-bar`): `ghcr.io/nichom01/neversoft/svc-declare:feat-foo-bar`

### Pull latest main-branch build

```
ghcr.io/nichom01/neversoft/<module>:latest
```

- **Mutable** — updated on every successful build of the `main` branch
- Never set by feature-branch builds
- Example: `ghcr.io/nichom01/neversoft/svc-declare:latest`

---

## Publish Guarantee

| Condition | Image published? |
|-----------|-----------------|
| Maven build succeeds, any branch | Yes |
| Maven build fails | No |
| GHCR temporarily unavailable | Skipped (non-fatal); workflow continues |

---

## Workflow Output

The `build-module.yml` reusable workflow exposes an additional output after this feature:

| Output | Type | Description |
|--------|------|-------------|
| `image_published` | `'true'` \| `'false'` | Whether the GHCR push succeeded |
| `image_ref` | string | Fully-qualified image reference with SHA tag, e.g. `ghcr.io/nichom01/neversoft/svc-declare:sha-a1b2c3d` |
