# Lightweight spec-driven development

## Status

Implemented; local documentation validation passed. Final-head GitHub Android CI
is pending for the updated PR. The owner authorized this workflow and the review
refinements; implementation approval does not authorize merging or publishing.

## Context / problem

Project constraints and release automation are documented, but individual changes
lack a consistent record of intent, acceptance criteria, and validation evidence.
PR #73 improves release handoff; preserve that work while adding the spec workflow.

## Goals

Give humans and coding agents short, durable project context and a proportional
spec workflow. Decide release intent before coding and apply it before PR handoff.

## Non-goals

No application redesign, new CI gate, release mechanism, agent-specific tooling,
version bump, merge, or publication.

## User-visible behavior

Contributor process only; Android runtime behavior remains unchanged.

## Technical constraints / invariants

Preserve the existing release parser, workflows, signing and CI gates, reservations,
preview system, and Gradle version constants. Retain existing architecture and
migration rules in AGENTS.md. Preserve historical PLAN.md content and open QA gaps.

## Proposed approach / plan

1. Add mission and technical context grounded in source, linking authoritative files.
2. Add a small spec guide and template, with the plan inside each spec.
3. Align AGENTS.md, CONTRIBUTING.md, README discovery, and the PR template.
4. Add a historical notice to PLAN.md without relocating or rewriting its evidence.
5. Validate Markdown links, release-template parsing, and documentation-only scope;
   update this record and the delivery PR, verify its actual release label.

## Acceptance criteria

- [x] Project context describes the current source and links authoritative build files.
- [x] Significant work gets criteria and a validation plan before coding; trivial work is exempt.
- [x] Existing authorization permits implementation without a redundant approval round.
- [x] Release guidance distinguishes actual labels, exact directives, and version estimates.
- [x] PLAN.md history and unverified device/live-YouTube checks remain intact.
- [x] Documentation links and template release-parser checks pass.
- [x] Only Markdown changes; application, workflows, scripts and versions are unchanged.
- [x] PR has the actual `release:skip` label and remains open for owner review.

## Validation plan

- Inspect new context against source, build configuration, CI, and release parser.
- Check relative Markdown file links, section anchors, balanced fences, and diff whitespace.
- Exercise the existing release parser with the PR template, labels, and exact directive.
- Compare changed paths and protected file contents against main.
- Read back PR metadata/label and report latest final-head CI status at handoff.
- Android builds/unit/lint/device tests: not run locally for documentation-only work;
  existing GitHub PR CI still applies. No physical-device, live-YouTube, or performance QA.

## Risks / edge cases

Documentation can drift; link source versions and update lasting conventions in the
same PR. A skip label mentioned only in prose would still default to patch release.
Historical pending QA must not become a claimed success through relabeling.

## Release intent

`release:skip`, explicitly requested for this documentation/process PR. Apply and
verify the GitHub label; no exact version, version-constant edits, or official APK.
The existing rolling preview behavior remains enabled.

## Implementation result

Added mission/technical context, the spec guide/template, and this first change spec.
Updated AGENTS.md, CONTRIBUTING.md, PR template, README discovery, and the PLAN.md
historical notice. Kept the plan inside each spec, trivial-change exemption, existing
implementation authorization, actual release metadata rules, and honest QA boundaries.
Corrected stale caption-discovery and onboarding names in agent guidance against source.

Initially planned to extend [PR #73](https://github.com/hoangkien1703/dual-sub-replay/pull/73),
which covered release handoff. It was merged concurrently before these changes were
uploaded. Deliver this work on `codex/spec-driven-development` against updated main,
preserving that merged guidance. This change uses the owner's explicit skip instruction;
no merge or publication is authorized.
No application, build, release-script/workflow, signing, version, or roadmap changes.

## Validation result

- Passed: relative Markdown file links and heading anchors across all 10 changed
  documents; balanced fences/comments, final newlines, and whitespace checks.
- Passed: eight checks against the existing release parser: untouched template defaults
  to patch; prose-only skip still defaults to patch; each of four actual labels selects
  its mode; a standalone exact version works; label/directive conflicts are rejected.
- Passed: diff scope is Markdown only; protected source directories are unchanged.
  PLAN.md's original scope/execution/QA content is byte-for-byte preserved below its notice.
- Reviewed technical claims against actual source/build/CI and release metadata handling.
- Verified [PR #74](https://github.com/hoangkien1703/dual-sub-replay/pull/74) is open and
  has the actual `release:skip` label. Final-head Android CI and preview availability
  remain pending at this update; earlier CI is not evidence for the new head.
  The owner reviews these before merging.
- Not run locally: Android unit/lint/build, managed-device, physical-device, live YouTube,
  and performance tests. This documentation-only change does not establish runtime behavior
  or close any historical QA gap. No release code/YAML/shell was modified.
