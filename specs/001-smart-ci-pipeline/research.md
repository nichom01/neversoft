# Research: Smart CI Pipeline

## Change Detection Strategy

**Decision**: Use `git diff --name-only origin/<base-branch>...HEAD` for change detection in GitHub Actions

**Rationale**: Native git, zero third-party action dependencies, works on all GitHub-hosted runners. Outputs a list of changed file paths which can be filtered by module directory prefix. For pull requests, `github.event.pull_request.base.sha` provides the exact merge base.

**Alternatives considered**:
- `dorny/paths-filter` (third-party action) — popular but adds an external dependency with its own release cadence
- `tj-actions/changed-files` — similar tradeoff; vendor churn risk
- GitHub's native `on.push.paths` filter — only controls workflow triggering, not selective job execution within a workflow

**Resolution**: Script the detection inline using `git diff` and write the affected module list to `$GITHUB_OUTPUT`. This is portable and auditable.

---

## Reusable Workflow Composition

**Decision**: Use GitHub's `workflow_call` trigger for reusable workflows; orchestrate via `jobs.<id>.uses`

**Rationale**: Native GitHub Actions mechanism. Supports typed inputs and outputs, allowing downstream workflows to consume results from upstream ones. No external tooling required.

**Alternatives considered**:
- Composite actions (`action.yml`) — suitable for single-step wrappers, not for multi-job pipelines with matrix strategies
- External CI orchestrators (Nx, Turborepo) — appropriate for JavaScript monorepos; not a good fit for a Maven/Java monorepo

**Resolution**: Each pipeline stage (detect, build, test-unit, test-integration, report) is a reusable workflow file in `.github/workflows/`. Orchestrator workflows (`pr-pipeline.yml`, `on-demand.yml`) call them via `uses: ./.github/workflows/<name>.yml`.

---

## Module Identification

**Decision**: Modules are identified by the set of known top-level service directories; the list is maintained as a static array in the detection workflow

**Rationale**: The repository has a fixed, small number of services (`declare-svc`, `audit-svc`, `validate-svc`, `risk-svc`). A hardcoded list is simpler and more auditable than directory auto-discovery. When a new service is added, updating the list is a one-line change to the detection workflow.

**Alternatives considered**:
- Auto-discovery via `find . -maxdepth 1 -name "*-svc" -type d` — flexible but hides the module set from code review; easy to accidentally include scratch dirs
- External manifest (`modules.json`) — adds a file that must stay in sync with directory structure; another thing to forget to update

**Resolution**: Static array in the change-detection workflow. The spec assumption (FR-010: "no manual configuration needed") is adjusted: adding a module requires a single-line change to the workflow, which is the minimum possible friction.

---

## Parallel vs. Sequential Module Execution

**Decision**: Use GitHub Actions matrix strategy to run module builds and tests in parallel

**Rationale**: Independent modules (declare-svc, audit-svc, validate-svc, risk-svc) have no runtime dependency on each other. Running them in parallel reduces total wall-clock time proportionally.

**Alternatives considered**:
- Sequential `needs:` chain — simpler but slower; each module waits for the previous one
- Fan-out/fan-in via `strategy.matrix` — the correct GitHub Actions pattern for this use case

**Resolution**: The build and unit-test workflows accept a `module` input and run against a single module. The orchestrating workflow uses `matrix: { module: ${{ fromJson(needs.detect.outputs.affected_modules) }} }` to fan out across affected modules.

---

## Integration Tests Placement

**Decision**: Integration tests (`it-tests` module) run as a separate job that is triggered when any service module changes or when `infra/` changes

**Rationale**: Integration tests require a running environment (Docker Compose with PostgreSQL/Debezium). They are slow and environment-dependent. They should run after all unit tests pass, not in parallel with per-module unit tests.

**Alternatives considered**:
- Always run IT tests on every PR — wastes time when only documentation changed
- Run IT tests only when `it-tests/` directory changes — misses the case where a service change breaks integration behaviour

**Resolution**: If `detect` identifies any of `{declare-svc, audit-svc, validate-svc, risk-svc, infra, it-tests}` as changed, the IT tests job is enqueued after unit tests pass. The IT tests job uses Docker Compose to spin up the environment.

---

## Maven Build Commands

**Decision**: Per-module Maven build uses `mvn -pl <module> -am package -DskipTests`; unit tests use `mvn -pl <module> -am test`; IT tests use `mvn -pl it-tests -am verify -Dsurefire.skip=true`

**Rationale**: `-pl <module>` scopes the build to one module; `-am` (also-make) includes required upstream dependencies. This is idiomatic Maven and works without a root aggregator POM.

**Note**: The repository does not have a root aggregator POM. Each service has its own `pom.xml`. This means builds are always invoked from the service's own directory: `cd <module> && mvn ...` or `mvn -f <module>/pom.xml ...`.

---

## Manual Trigger Design

**Decision**: On-demand workflow uses `workflow_dispatch` with a `modules` input (comma-separated list or `all`) and a `branch` input (defaults to calling branch)

**Rationale**: `workflow_dispatch` is the GitHub native mechanism for manual runs. String inputs are simple to use from the UI. Accepting `all` as a special value avoids the user needing to know the module list.

**Alternatives considered**:
- Repository dispatch API — correct for programmatic triggering but adds complexity for human-initiated runs
- Multiple checkboxes per module — GitHub supports boolean inputs but they produce a cluttered UI and must be updated when modules are added

**Resolution**: Single text input. The workflow parses `all` → full module list, or splits on commas → specified modules.
