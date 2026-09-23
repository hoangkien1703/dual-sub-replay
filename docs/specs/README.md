# Feature specs

A spec records the problem, intended behavior, constraints, acceptance criteria,
release intent, and validation evidence for one coherent change. Keep ordinary specs
short; put the implementation plan in the same file and link existing explanations.

## Document map

| Location | Purpose |
| --- | --- |
| [AGENTS.md](../../AGENTS.md) | Operational instructions shared by coding agents and contributors |
| [docs/project/mission.md](../project/mission.md) | Stable product intent |
| [docs/project/tech-stack.md](../project/tech-stack.md) | Technical context and architecture boundaries |
| [ROADMAP.md](../../ROADMAP.md) | Single directional roadmap |
| `docs/specs/YYYY-MM-DD-short-change-name.md` | One change's behavior, plan, release intent, criteria and results |

## When to write one

Create or update a spec before implementation for new user-facing features,
significant bug fixes, architecture or multi-component behavior changes, risky
WebView/caption/playback work, persistence/migrations, CI/CD or release changes,
and performance work with measurable targets.

Typos, tiny documentation edits, and obvious small fixes without architectural impact
do not need a full spec. State `Not required — <reason>` in the PR and give brief,
checkable acceptance criteria. Risk and behavior matter more than line count.

## Start and lifecycle

1. Read [mission](../project/mission.md), [technical context](../project/tech-stack.md),
   [AGENTS.md](../../AGENTS.md), [ROADMAP.md](../../ROADMAP.md), related specs and source/tests.
   Repository documentation carries stable intent; chat history alone is insufficient.
2. Copy [TEMPLATE.md](TEMPLATE.md) to `YYYY-MM-DD-short-change-name.md` using the creation
   date and a lowercase kebab-case name. Add a specific suffix if that name already
   exists. Update a relevant active spec rather than duplicating it.
3. Define behavior, acceptance criteria, validation, release intent, and the small
   implementation plan before coding. Revise these when investigation changes scope.
4. Implement the smallest coherent solution, validate, and record actual results and
   deviations. Update lasting product/architecture/convention decisions in the relevant
   permanent docs in the same PR.
5. Link the spec in the PR. Apply/verify release metadata, report CI/preview status,
   and leave review and manual merge to the owner. Existing automation handles release.

Lifecycle: idea → feature spec (including plan and release intent) → focused branch →
implementation → tests/validation → PR → green final-head CI and preview review →
owner manually merges → automatic version bump and official release (or preview only
when skipped). Put spec changes on the feature branch too; an already-active focused
branch can be used while drafting. Feedback can return work to earlier stages.

| Status | Meaning |
| --- | --- |
| Draft | Scope or material decisions are still unresolved. |
| Approved for implementation | Existing owner authorization covers this scope; note that authorization. This does not grant merge/publication permission. |
| Implemented | Code/docs are written; list incomplete validation explicitly. |
| Validated | Required acceptance checks have evidence; optional/unavailable checks remain clearly identified. |
| Superseded | Retain the record and link its replacement and reason. |

A request to implement an agreed change already authorizes that implementation. Do not
add an approval round just to move a status, or invent owner approval. Ask only when a
material scope/product decision is unresolved. CI success does not approve a spec or PR.
Keep implemented specs as historical records; a new change should link the earlier
record rather than erase its evidence. There is no global implementation-plan file;
each significant change owns its plan and results in its feature spec. Existing
[QA records](../qa/issues-56-59/README.md) retain historical evidence and unresolved checks.

## Release intent is a decision, not automation

Record one intent and its reason before implementation where possible, and resolve it
before opening the PR. Follow explicit owner instructions; otherwise preserve the
existing patch default, including docs/workflow work. Recommend alternatives when
appropriate, but do not silently change the owner's release policy.

| Intent in spec | Actual PR metadata |
| --- | --- |
| `release:patch` | No override (default patch), or the single GitHub label `release:patch` |
| `release:minor` | Single GitHub label `release:minor` |
| `release:major` | Single GitHub label `release:major` |
| `release:skip` | Single GitHub label `release:skip`; preview continues |
| `Release-Version: X.Y.Z` | One standalone exact-version line in the PR body, outside HTML comments and code fences; no release label |

The [release parser](../../tools/automatic_release.py) reads GitHub labels and exact
PR-body directives, not specs, checkboxes, PR titles, or descriptive release prose.
Use at most one release label OR one directive, never both. Remove superseded overrides
when intent changes without touching unrelated labels, and verify saved metadata.
If a required label cannot be applied, report the missing action explicitly and do
not call the PR ready to merge: writing `release:skip` in its body still defaults to patch.

Choose the release *type* early. Patch/minor/major numbers are estimates until reserved,
since intervening releases/reservations can advance the baseline. Inspect tags, published
and draft releases, and the Gradle baseline before estimating; report unavailable
history instead of promising a number. An exact version must exceed all existing/reserved
stable versions. Do not manually edit `appVersionName` or `appVersionCode` in normal PRs.
Once reserved, use the existing [retry procedure](../../README.md#automatic-official-releases);
editing intent does not change a reservation.

## Evidence proportional to the change

Write exact commands/scenarios, expected results, and necessary environments before
implementation. Record pass/fail/not-run/not-applicable with reasons afterward. Unit,
lint/build, emulator/managed-device, physical-device, live YouTube, and performance
checks establish different things; offline fixtures cannot satisfy live validation.
For performance work include baseline, target, device/build, and repeatable measurement.
For docs-only work check links, consistency, and affected template behavior; do not
claim Android runtime validation. Existing PR CI still runs its configured checks.
