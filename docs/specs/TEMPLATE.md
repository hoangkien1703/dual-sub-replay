# <Feature / change name>

## Status

Draft | Approved for implementation | Implemented | Validated | Superseded

Keep one status. Note existing scope authorization or unresolved decisions; link a
replacement if superseded. Implementation authorization does not authorize merge/release.

## Context / problem

What problem are we solving? Link the issue, related spec, or reproduction evidence.

## Goals

Concrete outcomes.

## Non-goals

Explicit scope boundaries.

## User-visible behavior

Before/after behavior, including failures. For infrastructure/docs, say so explicitly.

## Technical constraints / invariants

Link only relevant [technical invariants](../project/tech-stack.md#architecture-invariants)
and source/tests. Include migration/security/release constraints that affect this work.

## Proposed approach / plan

Small implementation steps and affected components; keep the plan here.

## Acceptance criteria

Replace these prompts with objectively testable, change-specific criteria.

- [ ] Expected behavior or measurable target under a named scenario.
- [ ] Relevant existing behavior/invariant remains intact under a named regression scenario.
- [ ] Required validation below has recorded evidence.

## Validation plan

Specify exact commands/scenarios, expected results, and environment. Remove irrelevant
rows or mark them not applicable with a reason; do not make every category mandatory.

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | | |
| Android lint/build | | |
| Managed-device/emulator | | |
| Physical-device/manual | | |
| Live YouTube | | |
| Performance (baseline/target) | | |
| Documentation/process | | |

## Risks / edge cases

Important failure modes and compatibility risks only.

## Release intent

Choose one: `release:patch`, `release:minor`, `release:major`, `release:skip`, or exact
`Release-Version: X.Y.Z`. Give the reason before coding where possible and before PR
creation. Follow the [release metadata rules](README.md#release-intent-is-a-decision-not-automation).
This entry does not control automation. Numbers inferred from a bump type are estimates
until reserved; do not change Gradle version constants. Record actual applied PR metadata
in the PR, or the missing action if permissions prevented applying it.

## Implementation result

Fill after implementation: what changed and meaningful deviations from this plan.

## Validation result

Fill with actual command/scenario results and evidence links. Distinguish passed,
failed, not run, and not applicable with reasons. Record remaining criteria and who or
what environment can verify them. Never claim physical-device/live-YouTube success
from offline/emulator tests. Link the PR for current final-head CI and review status.
