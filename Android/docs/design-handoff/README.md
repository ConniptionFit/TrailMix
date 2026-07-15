# Handoff: TrailMix.ai — Android App

## Overview
TrailMix.ai is a text-only notes app for calls: system audio is captured locally into a live transcript (no bots joining the call, no audio/video playback UI — audio itself is processed and deleted immediately, never stored). The user types raw fragments during the call; after the call, AI merges fragments + transcript into a structured note. From a Chat & Recipes screen, saved prompts turn the note into an email, spec, or ticket.

This package covers the **Android app**, designed as a fresh, mobile-native pass — not a port of the desktop three-pane layout. Simplicity is the top priority: one primary view at a time, sequential navigation instead of simultaneous panes.

## About the Design Files
`TrailMix Android.dc.html` is a **design reference built in HTML**, using a generic Android device-frame mockup (status bar / gesture nav) to show scale and proportion — it is not production code and the frame itself is a stand-in, not a component to reuse. The task is to **recreate these screens natively** using Jetpack Compose (or the project's existing Android UI toolkit if one exists), following Android's own layout and navigation idioms.

## Fidelity
**High-fidelity.** Colors, typography, spacing, and screen structure below should be recreated pixel-accurately. Where oklch() colors are given, convert to the equivalent value in your platform's color system (e.g. Compose `Color`).

## Product decisions locked for this pass
- **Full in-call capture on Android** — the app captures live transcript + lets the user type fragments during the call itself (not desktop-only capture).
- **Transcript is a separate full-screen view**, reached from the note — not a persistent tray (no room for that on a phone).
- **Primary use case is editing/reviewing notes** — the note detail screen is the app's most-used surface; capture and chat are secondary flows reached from it.
- **Dark mode follows system setting** by default (no manual override shown in this pass, though a toggle exists — see Settings, screen 6).
- **No model-training opt-in/opt-out UI** — TrailMix Android does not present any data-sharing toggle. Do not build one.

## Screens / Views
Reference frame size: 412×892 (a generic Android phone). All screens share: background `#ffffff` light / `oklch(18% 0.006 260)` dark, primary text `oklch(20% 0.005 80)` light / `oklch(92% 0.004 80)` dark, dimmed/secondary text `oklch(50% 0.005 80)` light / `oklch(62% 0.006 260)` dark, card fill `oklch(97% 0.004 80)` light / `oklch(24% 0.008 260)` dark, hairline borders `oklch(90% 0.004 80)` light / `oklch(30% 0.01 260)` dark. Font: Roboto (Android system font). Accent — amber `oklch(58% 0.15 55)` (primary actions, fragment-sourced content); teal `oklch(55% 0.11 200)` (transcript-sourced content, used only in the note diff view).

### 1. Home
**Purpose:** Landing screen — list of notes, one upcoming meeting from calendar sync, and a way to start a new note/call.
**Layout:** Top row: "TrailMix" wordmark (22px/600) + a circular avatar placeholder (34px) top-right. Below: section label "Upcoming — from calendar" (11px/600, uppercase, `.04em` letter-spacing, dimmed), then one meeting card (`12px` radius, card-fill background, `14px 16px` padding) showing title (14.5px/500) and time (12.5px/400, dimmed) as a space-between row. Then section label "Notes", followed by a scrollable list of note rows (`14px 12px` padding, hairline bottom border), each with a title (15.5px/500) and one-line preview (13px/400, dimmed). A floating action button (56×56, `16px` radius, amber fill, white "+" glyph at 26px) is pinned bottom-right, `24px` from the bottom/right edges, with a soft drop shadow.

### 2. Live capture — during the call
**Purpose:** Active-call screen. Shows recording status, a small live-transcript preview, and a larger note-taking area for the user's own fragments.
**Layout:** Top: a small red recording dot (9px circle, `oklch(55% 0.18 25)`) + "Recording · 12:04" (13px/500, dimmed) row. Below: call/note title (19px/600). A "Live transcript" card (card-fill bg, `12px` radius, `12px 14px` padding) shows a label (10px/600 uppercase, dimmed) and one running line of transcript (13px/400, dimmed, quoted). Below that, a "Your notes" label followed by the user's own typed fragments rendered as plain editable text (15px/400, full primary-text color, `1.7` line-height) with a blinking cursor — this is a live textarea in the real app. Bottom: a full-width "End & Merge" button (`14px` radius, filled with the same recording-red `oklch(55% 0.18 25)`, white text, 15px/500, `14px` vertical padding) that ends the call and triggers the merge.

### 3. Note detail — primary editing surface
**Purpose:** The main, most-used screen — the structured note after merge, with quick access to its transcript and chat.
**Layout:** Top bar: a back chevron (‹, 22px, dimmed) left, and a "Sources shown"/"Show sources" pill toggle right (1px bordered, `100px` radius, `6px 12px` padding, 12px/500, dimmed text). Below: metadata "Today · 45 min" (12px/400, dimmed) then the note title (20px/600). Body: scrollable paragraphs (15px/400, `1.75` line-height, primary text color) where clauses sourced from the user's typed fragments are wrapped in an amber-tinted inline span (`oklch(93% 0.05 55)` background) and clauses sourced from the transcript get a teal-tinted span (`oklch(93% 0.035 200)` background) — both with `3px` border-radius. **Important:** highlighted-span text must stay a fixed dark color (`oklch(22% 0.01 80)`) regardless of light/dark app mode, since the tint backgrounds are always pale — don't let it inherit the page's dark-mode light text color, or it becomes unreadable. When "Show sources" is off, spans render as plain transparent-background text in the page's normal text color. Bottom: a two-item row acting as navigation, "Transcript" and "Chat & Recipes" (13px/500, equal-width, `14px` padding, dimmed / amber-colored respectively) — tapping either pushes to screens 4 or 5.

### 4. Transcript — full screen
**Purpose:** Full call transcript, reached from the note detail screen's bottom nav.
**Layout:** Top bar: back chevron + "Transcript" title (15px/500), `1px` bottom border. Body: scrollable speaker-labeled lines (14px/400, `1.7` line-height, dimmed body text, speaker name bold + full primary-text color), `16px 20px` padding, `14px` gap between lines.

### 5. Chat & Recipes
**Purpose:** Reached from note detail. Chat with the AI about the note; apply saved "Recipes" (prompt templates) to produce exports.
**Layout:** Top bar matches Transcript screen ("Chat & Recipes" title). Message list: user messages right-aligned in an amber-filled bubble (white text, `14px 14px 2px 14px` radius), assistant messages left-aligned in a card-fill bubble (primary text color, `14px 14px 14px 2px` radius), both 14px/400 with `1.4` line-height, `10px` gap between messages, `16px 20px` padding. Bottom, pinned: a horizontally-scrollable row of Recipe chips (1px bordered, `100px` radius, `8px 13px` padding, 12.5px/500) — "Follow-up email", "Create ticket", "Summarize" shown; more can scroll into view — followed by a full-width pill text input ("Ask anything…", 1px border, `100px` radius, `12px 16px` padding, dimmed placeholder text).

### 6. Settings
**Purpose:** Dark mode control and a brief privacy/security disclosure. Intentionally minimal — no data-sharing toggle.
**Layout:** Title "Settings" (22px/600). A single row: "Dark mode" (15px/500) + subtitle "Matches system setting" (12.5px/400, dimmed), with a track-and-thumb switch on the right (46×26px track, `100px` radius, amber when on / neutral gray when off; 20px white thumb, animates between `left: 3px` off and `left: 23px` on), bounded by hairline borders above and below the row. Below: section label "Privacy & security" (same label style as Home), then two short lines of body copy (13.5px/400, dimmed, `1.6` line-height): "Zero-retention audio — deleted immediately after transcription, never stored." and "SOC 2 Type II certified · GDPR compliant." **Do not add any further rows here** (e.g. no training-data opt-in) — this section is a disclosure only.

## Interactions & Behavior
- **FAB (Home) →** starts a new note/call, opening screen 2 (Live capture).
- **"End & Merge" (Live capture) →** ends recording, runs the AI merge, and opens screen 3 (Note detail) with sources shown by default.
- **"Show sources" toggle (Note detail) →** flips a boolean; shows/hides the amber/teal inline tinting. Defaults to **on** right after a merge so the user can review provenance before it's implicitly accepted.
- **"Transcript" / "Chat & Recipes" (Note detail bottom row) →** navigate to screens 4 / 5 respectively (standard push navigation with back support, not tabs — only one is visible on screen at a time).
- **Recipe chips (Chat & Recipes) →** tapping one submits that recipe's prompt against the current note in chat; result appears as an assistant bubble the user can copy or export.
- **Dark mode switch (Settings) →** overrides the system default for this app; persist the choice.
- Back navigation throughout should use the OS back gesture/button in addition to the in-app back chevron.

## State Management
- `notes: Note[]` — id, title, preview, body (with per-sentence provenance tags `fragment` | `transcript` for diff rendering), createdAt.
- `activeCallState` — recording boolean, elapsed time, live transcript buffer, user fragment buffer (while screen 2 is active).
- `showSources: boolean` — per note, defaults to `true` right after merge.
- `darkModeOverride: boolean | null` — null = follow system.
- `recipes: {name, prompt}[]` — static list for this pass; chip row on screen 5.
- Chat messages per note: `{role: 'user'|'assistant', text}[]`.

## Design Tokens
**Colors** — see the per-screen shared palette above; convert oklch → Compose `Color`/ARGB as needed.
**Typography** — Roboto, weights 400/500/600. Scale used: 10–11px (uppercase labels), 12–13.5px (meta/dimmed/body-small), 14–15.5px (standard body/UI), 19–22px (screen/note titles, weight 600).
**Spacing/radius** — card & button radius 12–16px; pills/switch tracks fully rounded (100px); standard padding 12–20px; hairline borders 1px using the border tokens above.

## Assets
No image/icon assets — fully typographic UI, consistent with the desktop app. The FAB "+" and back "‹" are text glyphs in the reference; use your platform's standard icon set for these in production (e.g. Material icons) rather than literal glyph characters.

## Screenshots
- `screenshots/01-screen.png` — 1a Home, 1b Live capture (side by side)
- `screenshots/03-screen.png` — 1c Note detail, 1d Transcript (side by side)
- `screenshots/05-screen.png` — 1e Chat & Recipes, 1f Settings (side by side)

## Files
- `TrailMix Android.dc.html` — all 6 screens (Home, Live capture, Note detail, Transcript, Chat & Recipes, Settings) in a single reference file; open in a browser to inspect exact spacing/copy.
- `TrailMix_v2.md` (see separate doc, not duplicated here) — the product-decision notes this Android pass was scoped from.
