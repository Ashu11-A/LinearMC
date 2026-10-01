# Documentation Requirements

This document defines what the LinearMC documentation suite must satisfy.
Every document listed in `docs/README.md` is written against these requirements.

## 1. Functional requirements

- **FR-1 Audience tiers.** Three readers must each find their path in under a minute:
  server operators (install, run, monitor, roll back), curious non-experts
  (what it is, why it exists, is it safe), and contributors (build, port, release).
- **FR-2 Information hierarchy.** Overview → Core Concepts → Architecture →
  Configuration → Operations → Reference → FAQ. No document assumes another
  beyond the README and the Concepts page.
- **FR-3 Plain language.** Active voice, short sentences, bullet points and
  tables over paragraphs. No source-code snippets, class names, or config
  dumps longer than a single short block. File paths and setting names are
  fine; implementation internals are not.
- **FR-4 Diagrams, not walls of text.** `architecture.md` explains structure
  and data flow with Mermaid diagrams (component + lifecycle). Diagrams must
  match the code, not an aspiration.
- **FR-5 Honest numbers.** Performance claims state what was measured, on
  what, and what was NOT measured. Stale benchmark tables are banned; only
  conclusions that still hold may be repeated.
- **FR-6 Operator precision.** Every setting documents: where it is set,
  its default, and what happens on a bad value. Every warning log line in
  the docs must exist in the code, quoted exactly.
- **FR-7 Safety first.** Crash-safety limits, the level-12+ hazard, backup
  requirements, and the rollback path appear wherever an operator could go
  wrong, not just in one page.
- **FR-8 No duplication.** Each fact lives in exactly one document; others link.
  The configuration reference is the single source for defaults.

## 2. Non-functional requirements

- **NFR-1 Verified.** Every factual claim traces to the current code or to a
  checked-in config/workflow file. Unverifiable claims are removed, not hedged.
- **NFR-2 Link integrity.** Every internal link resolves; every referenced file
  path exists at the stated location. Zero broken links.
- **NFR-3 Freshness.** No document describes pre-`linear-core` layouts,
  pre-async flush behavior, or old defaults. Version support statements
  (Folia/Canvas/Horizon, MC lines) match the tree as of the rewrite commit.
- **NFR-4 Lint-clean.** Markdownlint-clean, Mermaid syntax valid, consistent
  heading depth (H1 title, H2 sections, H3 max), tables used for reference data.
- **NFR-5 Review-gated.** Operator-facing documents pass a verification table
  (setting → code location → documented value) reviewed before replacing
  the old text.
- **NFR-6 Scoped diff.** The rewrite commit touches only `*.md` files.
