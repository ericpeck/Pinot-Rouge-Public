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

### Build-classpath advisories (not app runtime)

These coordinates appear in GitHub's Gradle snapshot because Android Gradle Plugin (and Jetifier / bundletool) pull them. They were **not** on `:app` `debugRuntimeClasspath` at `b772db3`. This build pins them on every resolvable project configuration and rewrites the known parent POMs. They are **not** added to the app.

| Coordinate | Parent in the submitted graph | Why it is loaded | Pin | Review |
|---|---|---|---|---|
| `org.bitbucket.b_c:jose4j` (GHSA-3677-xxcr-wjqv, compressed JWE DoS) | `com.android.tools.build:bundletool` | App Bundle / Play signing JWT handling inside AGP | 0.9.6 | Loaded with AGP. The vulnerable JWE path is not a Pinot Rouge call; re-check when shipping `bundleRelease`. |
| `org.jdom:jdom2` (GHSA-2363-cqg2-863c, XXE) | `jetifier-processor` | Support-library AndroidX conversion | 2.0.6.1 | Jetifier is off (`android.enableJetifier` is unset). The processor still sits on AGP's plugin classpath. |
| `org.apache.commons:commons-lang3` (GHSA-j288-q9x7-2f5v) | `commons-compress` via AGP | AGP archive handling | 3.18.0 | Build-only string/recursion issue. |
| `org.bouncycastle:bcpkix-jdk18on` / `bcprov-jdk18on` (GHSA-wg6q-6289-32hp, GHSA-c3fc-8qff-9hwx) | `sdk-common`, `builder`, `apkzlib` | APK signing / SDK lib | 1.84 (with `bcutil-jdk18on` kept on the same version) | LDAP / PKIX issues in tooling that signs or talks to SDK repositories, not SMS code. |
| `org.apache.httpcomponents:httpclient` | `analytics-library:crash` selects **4.5.14**; `httpmime:4.5.6` still *declares* 4.5.6 | AGP HTTP | Force `httpclient` and `httpmime` to 4.5.14 so the 4.5.6 node is not selected | GitHub's graph listed both; 4.5.14 is the patched 4.x line. Confirm the next `main` dependency-submission snapshot no longer contains 4.5.6 as a resolved node. |

CI greps the debug runtime inventory and fails if jose4j, jdom2, or the Bouncy Castle jdk18on artifacts appear there.

