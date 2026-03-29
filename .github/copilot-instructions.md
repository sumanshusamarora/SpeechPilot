# Copilot Instructions

This file defines expectations for coding agents working in this repository.

It establishes how work should be approached, how code should be written,
and what constraints must be respected to keep the codebase consistent,
maintainable, and production-ready.

---

## Scope

- This repository contains a native Android application and supporting modules.
- All processing is expected to run locally unless explicitly stated otherwise.
- Do not introduce backend services, APIs, or cloud dependencies unless explicitly requested.
- Do not assume external systems exist unless defined in code or documentation.

---

## Architecture Map

Follow the existing module structure. Do not introduce parallel structures.

Expected modules:

- `app/` → application entrypoint and wiring
- `ui/` → screens, composables, view models
- `session/` → lifecycle and orchestration
- `audio/` → microphone capture and audio frames
- `vad/` → speech activity detection
- `segmentation/` → speech segment buffering
- `pace/` → rate estimation and rolling metrics
- `feedback/` → decisioning and output
- `data/` → persistence (Room / repositories)
- `settings/` → user configuration

If structure evolves, follow existing patterns instead of introducing new ones.

---

## Canonical References

Before implementing changes, review:

- `README.md`
- `docs/phase1_architecture.md` (or equivalent)
- relevant module code

If behavior is unclear:
- trust the current implementation
- update docs if inconsistencies are found

---

## Invariants To Preserve

These must not be broken:

- Clear separation between UI and core logic
- Session orchestration is centralized (no scattered control flow)
- Audio processing does not run on the main thread
- Feedback decision logic is separated from feedback execution
- No hidden global mutable state
- Local-first processing (no implicit network usage)

If a change violates any invariant:
- explicitly call it out
- do not silently introduce it

---

## Implementation Rules

- Reuse existing modules before creating new ones
- Keep UI thin; move logic into domain/session layers
- Prefer explicit data models over maps or loosely typed data
- Avoid speculative abstractions
- Do not duplicate logic across modules

When adding new code:
- place it in the correct module
- follow naming conventions already in the repo
- keep functions small and focused

---

## Change Discipline

For every task:

1. Understand the scope
2. Identify affected modules
3. Implement the smallest complete change
4. Validate behavior
5. Update documentation if required

Avoid:
- broad refactors unrelated to the task
- mixing multiple concerns in one change
- introducing new architecture layers without need

---

## Validation Checklist

Run relevant checks after changes:

```bash
./gradlew lint
./gradlew test

For build validation:

./gradlew assembleDebug

Prefer:

targeted tests during iteration

full validation before completion



---

Coding Style

Follow idiomatic Kotlin

Use data classes for state models

Prefer immutability where practical

Use sealed classes for state/event modeling where useful

Keep functions short and readable

Avoid deeply nested logic


Avoid:

large monolithic classes

hidden side effects

excessive nullability



---

Android-Specific Expectations

Do not block the main thread

Use coroutines and Flow for async and streaming work

Keep ViewModels responsible only for UI state orchestration

Keep Composables free of business logic

Use lifecycle-aware components

Audio and processing must run on background threads



---

Real-Time Processing Constraints

This application contains real-time audio processing.

Agents must:

keep audio frame processing lightweight

avoid unnecessary allocations in hot paths

minimize latency between detection and feedback

avoid heavy computation per frame

batch or segment processing where possible


Prefer:

simple, predictable logic

rolling window calculations

incremental updates



---

Testing Expectations

Add unit tests for non-trivial logic

Add regression tests for bug fixes

Do not skip tests for core modules


Priority areas:

pace estimation logic

rolling window behavior

feedback decision rules (cooldown, debounce)

session state transitions


Mirror source structure for tests where possible.


---

Documentation Hygiene

Update documentation when behavior changes

Keep docs aligned with actual code

Do not leave stale documentation

Do not create unnecessary docs


If docs and code diverge:

update docs to match implementation



---

Refactoring Rules

Refactor only when necessary.

Allowed:

improving clarity within task scope

removing duplication encountered during work


Not allowed:

repo-wide refactors without request

introducing new architecture patterns unnecessarily

rewriting working code for style alone



---

Dependency Rules

Before adding a dependency:

check if Android SDK or existing code suffices

ensure it is necessary

ensure it reduces complexity


Avoid:

large frameworks for small problems

unnecessary coupling



---

Performance Considerations

avoid excessive allocations in tight loops

keep processing predictable and stable

minimize latency-sensitive operations

avoid blocking threads unnecessarily


When unsure:

prefer simpler implementations



---

Data and Privacy

do not introduce external data transmission

treat all audio-related data as sensitive

avoid storing raw audio unless explicitly required

persist only minimal necessary data



---

Output Expectations

Each completed task should result in:

working, runnable code

no regressions in existing behavior

passing tests where applicable

updated documentation if needed


If incomplete:

clearly state what remains

do not present partial work as complete



---

Non-Negotiables

do not introduce breaking changes silently

do not bypass lint or tests

do not ignore failures

do not introduce hidden behavior changes

do not expand scope without instruction



---

Working Philosophy

keep changes minimal and precise

build on existing patterns

prefer clarity over abstraction

avoid unnecessary complexity

maintain consistency across the codebase


---

# 🧠 Why this version is strong

This now:

- matches your **AegisTrace-style discipline** (tight, operational, no fluff)
- is **generic but grounded in Android reality**
- gives Codex:
  - guardrails
  - constraints
  - workflow
  - performance expectations
- protects you from:
  - overengineering
  - accidental cloud creep
  - messy real-time code
