# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x (this repository) | Yes |
| Anything else | No published releases |

This project publishes **source**. There is no Play Store listing and no GitHub Releases APK from this repository.

## How to report a vulnerability

Use **GitHub Private vulnerability reporting** on this repository (Security → Report a vulnerability).

Please include:

- Affected commit SHA or tag
- What an attacker would need (another app on the device, USB debugging, physical access, a crafted SMS/MMS, and so on)
- A minimal reproduction that does **not** include real personal messages, photos, or phone numbers

Do **not** open a public issue with exploit steps, payloads, or personal data.

There is no separate security mailbox published for this project. If private reporting is unavailable on the repository, open a public issue that says only that you want to report a vulnerability, and wait for a maintainer to contact you through GitHub.

## What this app is not

- SMS and MMS are **not** end-to-end encrypted. Carriers and Android's shared message store can see filed messages.
- Holding a message in Filtered does **not** prevent every other authorized app from observing an incoming SMS. Android still offers `SMS_RECEIVED` to apps that hold that broadcast permission.
- A clean CI run, lint run, or secret scan is **not** a certification, compliance attestation, or a claim that the project is vulnerability-free.

## Dependency alerts

Pushes to `main` submit the resolved Gradle dependency graph (see `.github/workflows/dependency-submission.yml`) so GitHub can alert on Android/Maven libraries, not only Actions. Pull requests from forks do not submit the graph. Dependabot version-update PRs are not vulnerability coverage.
