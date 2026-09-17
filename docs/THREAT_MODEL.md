# Threat model (concise)

This is a working model for reviewers, not a certification. SMS/MMS is a hostile input channel on a device that already runs other apps.

## Assets

| Asset | Trust boundary |
|---|---|
| Message bodies and attachments in Android's shared store | Shared with any app that has SMS read access; owned by the platform, not solely by Pinot Rouge |
| Held bodies in Room and held photos in `held_media/` | App-private. Other apps should not read them without a platform bug or root |
| Filter rules | App-private. A malicious rule is a self-DoS / self-quarantine risk, not a remote code path |
| Contacts | Platform provider; read, not copied |
| Default SMS role | System-wide. Losing it means this app no longer intercepts arrivals |

## Actors

- **Curious or hostile apps** on the same device (exported components, FileProvider URIs, PendingIntents, clipboard, notifications)
- **Crafted SMS/MMS** (malformed PDUs, huge parts, deceptive filenames, regex bombs in user-authored rules applied to attacker-controlled text)
- **The user** (or someone holding the unlocked phone)
- **The carrier and Android** (they already see filed SMS/MMS)

There is no app backend to impersonate.

## High-level controls

- Four `ROLE_SMS` components stay exported behind the platform permissions Android requires. Do not disable them to “look locked down.”
- Completion receivers (`MmsDownloadReceiver`, `MmsSendReceiver`, `SmsSentReceiver`) are not exported. SMS/MMS send `PendingIntent`s are **mutable** because the platform writes result extras; they target those non-exported receivers.
- Notification `PendingIntent`s are immutable.
- FileProvider is not exported; paths are cache `mms_send/` and `mms_download/` only.
- `SENDTO` accepts only `sms` / `smsto` / `mms` / `mmsto`, with recipient and body length caps. Opaque and hierarchical SMS URIs are parsed from the scheme-specific part; `Uri.getQueryParameter` is not used.
- `MATCHES_REGEX` uses RE2 (linear-time). Unsupported syntax (lookaround, backreferences, and similar) pauses the whole rule rather than treating the condition as false. Pattern and haystack length caps remain.
- Auto Backup is off; D2D extraction rules exclude quarantine storage, pending-compose prefs, and WorkManager's database. Android's shared store is a **separate** ownership domain.

## Out of scope for this app to “fix”

- Encrypting SMS/MMS against the carrier
- Stopping `SMS_RECEIVED` listeners in other apps
- Making quarantined media anonymous to a rooted device
- RCS

## Residual risks worth knowing

- Mutable send/download `PendingIntent`s are required by the platform callback shape.
- OTP codes sit on the clipboard until a best-effort WorkManager clear. The worker stores a random ownership token, not the code. Android may delay the worker or deny a background clipboard read, so the code is not guaranteed to disappear at 60 seconds.
- Held inbound photos keep sender metadata (including location in EXIF) for the retention window.
- User-authored regex is RE2. Patterns that need lookaround or backreferences do not run; the filter is paused until the pattern is changed.
