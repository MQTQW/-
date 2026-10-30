# Local travel sample product context

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users and purpose

The MY PLAN owner is validating a personal-use travel research flow on a local computer before deciding how to connect it to the existing Android app. The artifact joins source notes, candidate places and an editable draft so the owner can inspect each stage.

## Confirmed constraints

This scoped extension inherits the app's approved warm, light sage/cream identity. It is a local document and operation tool, not a marketing page. No Android changes, server purchase or public deployment belong to this artifact. Source bodies, credentials and provider request payloads must not be embedded. Missing model/map configuration stays visible. Map POI matching never implies opening hours, reservation or travel feasibility is verified.

The current surface is the desktop-first report in [report.mjs](../report.mjs), delivered as [the 2026-10-28 local artifact](../../../delivery/travel-research-20261028/index.html). This product context is scoped to that report; it does not replace or revise the existing Android product documentation.

## Interaction

The owner switches days, adds candidates, edits proposed times/durations/notes, removes stops and exports a JSON draft. The browser holds edits in memory. The file does not write to MY PLAN and has no network API calls. Original public sources open only on deliberate link activation.

The local validator checks source and candidate references, valid times and durations, repeated places, time overlap, ordering, and map-estimated travel time or a suggested buffer. A blocking issue disables export. Passing these checks does not verify business hours, reservations or actual transport feasibility. Export retains source references and pending states, marks `savedToApp: false`, and states the validation scope.

## Delivered sample and provenance — 2026-10-28

The initial artifact contains a three-day Hangzhou draft with seven stops, twelve candidate places and two public source records. The sources are real earlier MCP captures, and the three image extractions come from earlier real DeepSeek recognition. This run reused those validated source and extraction results; a fresh MCP retrieval recheck timed out. Both retrieval and parsing therefore display “使用已验证样本”, rather than implying fresh retrieval or a new model/image call succeeded.

The map stage queried Amap in this run and produced seven matched POIs and four estimated travel legs. Five ambiguous places remain pending and were not automatically added to the draft. The draft and map stages remain partial. Place matching confirms the selected map location only; suggested duration, ordering, opening hours, tickets, reservations and actual travel conditions remain subject to review.

## Acceptance evidence

[checks.json](../../../delivery/travel-research-20261028/qa/checks.json) records 77 passing offline tests and successful day switching/restoration, a five-place pending filter, overlap blocking, visible cached provenance, and corrected export feedback. Its export exercise produced eight stops after adding one manual pending place to the seven-stop initial draft; both source records and the pending state were preserved. Eight is the edited test export count, not the initial sample count.

The [QA directory](../../../delivery/travel-research-20261028/qa) contains `desktop-top.png`, `desktop-full.png`, `mobile-top.png`, `mobile-full.png` and `overlap.png`. Desktop top PNG dimensions are 1265 × 712. The mobile DOM viewport was 375 × 812 with no horizontal overflow, while the provider's top PNG was 360 × 779; this capture difference is recorded in the checks. The final five captures were reviewed after the status, conflict-evidence and export-copy fixes, and the independent finish review returned **ship**, as reported by the coordinating agent.

This documentation pass checked the implementation, public artifact, Android color source and existing QA evidence locally. It did not rerun providers, change UI/business code, or inspect credentials. Token provenance and responsive behavior are recorded in [DESIGN.md](DESIGN.md).
