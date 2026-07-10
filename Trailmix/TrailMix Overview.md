# TrailMix Overview

> Local-first AI meeting notes for Linux. Capture dual-channel audio, transcribe offline with whisper.cpp, jot Mix-Ins, blend with a local LLM (Ollama), and keep everything on disk you control.

**Vault folder:** `Trailmix/` (sibling to `Powarr/` in the Obsidian vault).  
**Repo mirror of agent prompt:** root `CLAUDE.md` ≡ [[Master Prompt]].  
**Version:** v0.5.0 · **Last vault sync:** 2026-07-10

## What it is

| | |
|---|---|
| Product | TrailMix — Electron desktop app |
| Pitch | Capture meetings offline · Jot notes · Blend with AI · Keep everything local |
| Repo | https://github.com/ConniptionFit/TrailMix |
| Not | A cloud SaaS, a phone app, or a multi-OS capture stack (Linux Pulse first) |

## Core metaphors

| Name | Meaning |
|---|---|
| **The Trail** | Session library / sidebar history (searchable, foldered) |
| **The Mix** | In-meeting note editor + AI blend (“Mix-Ins” → enhanced notes) |
| **Mix-Master** | Offline chat agent over transcript + history |
| **Nuts and Bolts** | Settings (models, audio, themes, encryption, prompts) |
| **Campfire Preserves** | Filesystem folders under the save location |
| **Start / Resume Trail** | Begin or continue recording a session |

## Surfaces

| Surface | Entry | Notes |
|---|---|---|
| Hub | `renderer/index.html?mode=hub` | Home, Trail, History, Action Items, Calendar, Settings |
| Meeting | `renderer/meeting.html?sessionId=` | Live capture, Mix editor, transcript, in-meeting chat |
| Mini widget | `?mode=mini` | Always-on-top live line — **infra present, not launched from runtime yet** |

## Quick start (operator)

1. Install system deps: `ffmpeg`, `pulseaudio-utils`, build tools, Ollama, Node 18+
2. `./scripts/setup-whisper.sh` → models under `bin/whisper.cpp`
3. `npm install` → `npm start`
4. Nuts and Bolts: pick Whisper + Ollama models, mic/sink, optional encrypt-by-default
5. **New Meeting** → Start Trail → jot Mix-Ins → Stop → processing badges → summary/actions land on The Trail

## Doc map

| Note | Use when |
|---|---|
| [[Master Prompt]] | Agent sessions — always load first |
| [[Architecture]] | Main process, DB, paths, save pipeline |
| [[Live Capture & Transcription]] | Record path, Whisper workers, AEC/bleed |
| [[The Mix]] | Editor, enhance, document model |
| [[Mix-Master]] | Chat scopes, recipes, streaming |
| [[The Trail]] | List virtualization, FTS, folders, export |
| [[Processing Pipeline]] | Post-stop diarization + enrichment |
| [[Encryption & Privacy]] | AES, keys, shred, FTS exclusion |
| [[Settings & Configuration]] | Nuts and Bolts reference |
| [[IPC & Modules]] | Channel registry, module stubs |
| [[Development & Packaging]] | AppImage, scripts, legacy recovery |
| [[Troubleshooting]] | Common failures |
| [[Future Improvements]] | Living roadmap (To-Do + Complete) |

## Related products

- **Powarr** — separate vault folder `Powarr/`; same documentation house style and agent discipline. Do not cross-wire codebases.
