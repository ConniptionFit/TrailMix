# Encryption & Privacy

> Local-only AI. Optional AES-GCM session encryption. Passwords never touch `settings.json`.

## Threat model (product intent)

| Goal | Mechanism |
|---|---|
| No cloud STT/LLM | whisper.cpp + Ollama only |
| Optional at-rest secrecy | AES-256-GCM envelopes on `.trail.bak` + DB payload |
| No password persistence | In-memory `decryptionKeys` Map only |
| No accidental plaintext | Encrypted flag **blocks** save without password |
| Temp audio hygiene | Chunked secure shred after Whisper / finalize |

## Crypto (`encryption.js`)

| Detail | Value |
|---|---|
| Cipher | AES-256-GCM |
| KDF | PBKDF2-SHA512/SHA256 path as implemented — 100k iterations |
| Envelope | `{ salt, iv, tag, encrypted }` |
| Autosave | Reuse salt/key via `encryptionEnvelopes` (avoid PBKDF2 every tick) |

## Settings

| Setting | Default | Notes |
|---|---|---|
| `encryptByDefault` | `false` | New sessions get `encrypted: true` when on |
| `encryptionPassword` | **removed** | Stripped on load/save; SETTINGS_GET never returns it |

## UX flows

| Flow | Behavior |
|---|---|
| New encrypted meeting | Defer first disk write until password set; modal in meeting |
| Silent save while locked | Skip + `session:needs-encryption-password` |
| Unlock from Trail | Password modal → `calls:decrypt` / load; FTS reindex when unlocked |
| Bulk unlock | `calls:decrypt-multiple` |
| FTS | Encrypted/locked sessions **not** indexed |

## Secure shred (`lib/secure-shred.js`)

Chunked (~1 MB) `randomBytes` overwrite then unlink — used for chunk WAVs and temp dir cleanup. Avoids OOM on large files.

## Do / Don't

- **Don't** write passwords to settings, logs, or crash reports.
- **Don't** index ciphertext into FTS.
- **Do** prompt for password when `encryptByDefault` / `encrypted` before any persist.
- **Do** keep unlock keys process-local only (OS keychain = future item).

## Related

[[Architecture]] · [[The Trail]] · [[The Mix]] · [[Future Improvements]]
