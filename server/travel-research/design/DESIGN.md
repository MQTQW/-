---
name: MY PLAN local travel report
description: A scoped extension of the Android app for inspecting travel evidence and editing a local draft.
colors:
  background: "#f7f8f2"
  surface: "#fff"
  ink: "#344331"
  muted: "#5c6a56"
  primary: "#65794f"
  primary-hover: "#536540"
  soft: "#e7eddc"
  divider: "#dce3d4"
  pending-ink: "#785632"
  pending-background: "#f6efe2"
  error: "#a04430"
  matched-ink: "#435a31"
  subtle-surface: "#eef2e7"
  input-border: "#c5cfbc"
  focus: "#86996d"
typography:
  display:
    fontSize: "30px"
    fontWeight: 650
    lineHeight: 1.35
    letterSpacing: "-0.025em"
  headline:
    fontSize: "21px"
    fontWeight: 650
    lineHeight: 1.4
  title:
    fontSize: "18px"
    lineHeight: 1.45
  body:
    fontFamily: '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif'
    fontSize: "15px"
    lineHeight: 1.65
  label:
    fontSize: "12px"
rounded:
  empty-surface: "14px"
  primary-action: "12px"
  day-tab: "10px"
  field: "9px"
  quiet-action: "8px"
  status-tag: "5px"
spacing:
  compact: "6px"
  small: "8px"
  control: "12px"
  regular: "16px"
  section: "24px"
  desktop-column: "40px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.primary-action}"
    padding: "11px 21px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
  day-tab-selected:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.day-tab}"
    padding: "8px 17px"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.field}"
    padding: "9px 11px"
  tag-pending:
    backgroundColor: "{colors.pending-background}"
    textColor: "{colors.pending-ink}"
    rounded: "{rounded.status-tag}"
    padding: "2px 7px"
---

# Local report design

## Overview

**Creative North Star: "A draft beside its evidence"**

This document records the implemented local report in [report.mjs](../report.mjs), not a replacement visual identity. It extends the user's confirmed MY PLAN warm white and sage Android design for the personal travel flow in [PRODUCT.md](PRODUCT.md). The report is an operation and reading surface: stage status, candidate evidence and editable times need to remain easy to inspect.

The first viewport places the compact title and export action above a textual stage strip. The wider draft column sits beside candidate places; source records follow below. Verification remains visible where the owner chooses a place. No concept seed or new brand direction applies.

### Final visual evidence — 2026-10-28

The delivered [index.html](../../../delivery/travel-research-20261028/index.html) and [QA captures](../../../delivery/travel-research-20261028/qa) show this implementation. The desktop top capture is 1265 × 712 pixels. Mobile layout was checked at a 375 × 812 CSS viewport without horizontal overflow; the screenshot provider returned a 360 × 779 pixel top image. These are distinct measurements, not a claim of a 375 × 812 PNG.

The desktop and mobile top/full captures and `overlap.png` were recaptured after the three review fixes: cached-source status, conflict evidence in the visible document area, and export feedback. The independent finish review returned **ship**, as reported by the coordinating agent. The mechanical detector returned `[]` before those final fixes; it was not rerun for this documentation pass. [checks.json](../../../delivery/travel-research-20261028/qa/checks.json) records the interaction results and capture dimensions.

## Colors

The frontmatter records the report's actual CSS values. Shared identity colors were checked against [Android Color.kt](../../../app/src/main/java/com/example/myplan/ui/theme/Color.kt):

| Report token | Android source | Use |
| --- | --- | --- |
| `background` | `Bg` | Warm white page ground |
| `surface` | `CardVanilla` | Editable fields and reversed action text |
| `ink` | `TextDark` | Main text and headings |
| `primary` | `Accent` | Export action and selected day |
| `soft` | `AccentLight` / `CardMint` | Matched states, filters and notices |

These shared values are exact matches after expanding the report's shorthand white. Muted text, dividers, hover, focus, pending and error colors are report-specific extensions; they are not aliases for Android `TextSoft`, `Divider` or `Delete`. Warm brown labels indicate unresolved places, while sage marks a successful place match. Text always names the state alongside color.

## Typography

Use the local Chinese system sans stack from the body token. No external font is loaded. The desktop title, section heading and stop heading use the display, headline and title roles. Candidate headings and day headings are compact (16px); secondary copy is generally 12–13px. Status tags and source metadata are smaller (11px).

At the narrow layout, the page title becomes 26px, section headings become 20px and the subtitle becomes 14px. Numeric fields use tabular figures. Long place names and evidence wrap; source labels remain concise.

## Layout

The desktop page is capped at 1240px and leaves 36px on either side, with 42px top padding. The workspace uses a 1.16:1 draft-to-candidate grid and the desktop column spacing token. Sources form two columns below it. Thin dividers organize rows instead of wrapping each item in a card.

At 900px or less, page margins become 24px, columns become equal and their gap becomes 28px. At 680px or less, margins become 18px and both workspace and source list stack into one column; the draft precedes candidates. Export fills the available width, stages form a two-column grid and the day tabs share a row. Time and duration remain paired fields.

## Elevation & Depth

The report has no box shadows. Warm page ground, white editable fields, restrained tonal fills and quiet borders separate surfaces. Pending and error messages use distinct tinted backgrounds. No raster assets or decorative animation ship. Anchor scrolling is smooth by default and becomes immediate when reduced motion is requested.

## Shapes

The report keeps the app's soft form language while using the denser web radii recorded in the frontmatter. They do not redefine Android `RadiusCard`, `RadiusButton` or `RadiusInput`. Empty-state surfaces have the broadest curve; fields, tabs, actions and small state labels use progressively tighter corners. Inputs have a fine border; section and row separators remain straight.

## Components

- **Export action:** sage fill with white text, a darker hover and a pale disabled state. It remains disabled while the draft is empty or has a validation issue. After download, feedback confirms the export while the edit-state label continues to describe edits.
- **Day tabs:** a filled selected tab, outlined unselected tabs and visible keyboard focus. Arrow keys, Home and End change the selected day.
- **Editor rows:** place name, explicit match/pending label, paired start-time and duration fields, notes and source references. Conflicts appear above the day's stops and block export.
- **Candidate rows:** a wrapped place name, match/pending label, source excerpt, references and an add action. The filter distinguishes all places, matched places and pending places.
- **Source records:** title, source identifier, timestamps, body/image counts and a bounded excerpt. References jump to the corresponding source; an external source opens only when activated.
- **Focus:** interactive elements share a 3px focus outline with a 3px offset. Status feedback uses a live region; validation uses an alert.

## Do's and Don'ts

- **Do** preserve the confirmed MY PLAN warm white and sage identity and the report's actual token values.
- **Do** show cached provenance as “使用已验证样本” and keep pending verification visible beside each place and in exports.
- **Do** keep the first view focused on status, evidence and the editable draft, with clear field labels and quiet separators.
- **Don't** describe a map match as verification of opening hours, tickets, reservations or actual traffic feasibility.
- **Don't** replace the Android visual system or claim that report-specific colors and radii are exact Android tokens.
- **Don't** embed full source bodies, credentials or provider request payloads in this local artifact.
