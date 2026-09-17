# Privacy

This document describes **what the current source actually does**. It is not a legal privacy policy for a Play Store listing, and this repository does not ship an APK or a Google Play submission.

## Summary

- Filtering runs on the device. The application code does not include an HTTP client, analytics SDK, crash-reporting service, account system, or cloud backup of private data.
- SMS and MMS still travel through your carrier. Becoming the default SMS app does not encrypt those messages.
- Messages Pinot Rouge **files** go into Android's shared SMS/MMS provider. Other apps with the right permissions can read that store. Switching default SMS apps does not erase it.
- Messages Pinot Rouge **holds** live in an app-private Room database, with held photos as app-private files. That is a storage choice, not a guarantee that no other app can observe an incoming SMS.

## Data the app handles

| Data | Where it lives | Who else can see it |
|---|---|---|
| Filed SMS/MMS bodies and attachments | Android's Telephony provider | The default SMS app, and any app the user has granted SMS read access |
| Held (Filtered) message bodies | App-private Room database `pinot_rouge.db` | This app. Cloud Auto Backup is disabled. Device-to-device transfer excludes this database and its WAL/SHM files. |
| Held photos | App-private `held_media/` | This app. Stored **as received**, including any embedded metadata such as location. Device-to-device transfer excludes this directory. FileProvider paths do **not** include it. |
| Filter rules and settings | Room + DataStore | This app. DataStore is excluded from device-to-device transfer. |
| Pending compose drafts | `shared_prefs/pending_compose.xml` | This app. Device-to-device transfer excludes this file. Consumed into the compose screen and then cleared. |
| Contacts | Read through the Contacts provider when permission is granted | Used for display names and the “never filter contacts” check. Not copied into Room. |
| Clipboard (OTP copy) | System clipboard, marked sensitive. A random ownership token is stored in clip extras and WorkManager input — not the code itself. Clearing is **best-effort**: WorkManager's delay is a minimum, not an exact 60-second timer, and Android can refuse a background clipboard read. Unrelated clipboard content is never cleared. | Other apps can still read the clipboard while the code is there, subject to Android's clipboard restrictions |

## Notifications

Arrival notifications use MessagingStyle. They can show sender and message preview according to the user's notification settings. They are not a second encrypted channel.

## Backups and device transfer

`android:allowBackup="false"` disables Android Auto Backup for this app's private data. It does **not** control:

- Android's shared SMS/MMS store
- Other apps' backup settings
- Carrier copies of messages

On API 31+, `allowBackup=false` does not by itself disable device-to-device transfer. `res/xml/data_extraction_rules.xml` excludes the Room database (including WAL and SHM), DataStore, `held_media/`, `shared_prefs/pending_compose.xml` (SENDTO drafts), and the WorkManager database. MMS send/download PDUs live in the process cache; `cache` is not a supported data-extraction domain. These excludes were not verified by running an actual device-to-device migration.

Uninstalling the app or clearing its data removes the private database, rules, settings, and held media. It does not remove messages already written to Android's shared store.

## Network

The app does not open its own HTTP(S) connections. `android:usesCleartextTraffic="false"` is set so the application process will not use cleartext HTTP if a future change added a client.

MMS send and download use Android's `SmsManager` APIs, which talk to the carrier's multimedia messaging service. That traffic is **not** an application HTTP stack, is **not** end-to-end encrypted by this app, and must keep working for picture messages.

## Logs

Compose-prefill logging does not include recipient numbers or message bodies. MMS retrieve logs a local operation id, not the carrier Content-Location URL. Avoid attaching logcat captures that contain real notifications or SMS to public issues.

## What “on-device” does not mean

On-device filtering means Pinot Rouge evaluates rules locally. It does not mean:

- Messages are end-to-end encrypted
- Held messages are invisible to every other app on the phone
- The carrier cannot see SMS/MMS it transports
- A factory reset is required to leave the app
