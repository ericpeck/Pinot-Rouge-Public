# Contributing

Thank you for wanting to improve Pinot Rouge. This repository is the public application source. Please read [README.md](README.md) first.

## Before you write code

- Port the existing design. Do not invent a new visual system, rewrite user-visible copy, or add accounts, cloud services, telemetry, RCS, or custom cryptography.
- Keep `:core:rules` free of `android.*` / `androidx.*` imports. Pass values in through `EvaluationContext`.
- Do not add a network client to the app. Carrier MMS still uses the platform `SmsManager` APIs; that is not an app HTTP stack.
- Do not enable Material You `dynamicColor`. The accent comes from the Pinot theme.
- Do not remove or “simplify” the four `ROLE_SMS` components in `AndroidManifest.xml`.

## Development setup

JDK 21, Android SDK platform 37, and the checked-in Gradle wrapper. Set `sdk.dir` in gitignored `local.properties` or export `ANDROID_HOME`.

Enable hooks once per clone:

```bash
git config core.hooksPath .githooks
```

## The merge gate

Every change must pass:

```bash
./gradlew :core:rules:test :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin assembleDebug
```

`--rerun-tasks` or a run that actually executed the tasks is evidence. An all-`UP-TO-DATE` run executed nothing.

CI runs the same gate plus Android Lint. It does **not** run `:app:connectedDebugAndroidTest`.

If your change makes an existing test fail, retarget that test in the same PR, say why, and add a companion test for the new behaviour. Do not delete a test to make the build pass.

## Pull requests

- One focused change per PR, into `main`.
- Do not commit `local.properties`, keystores, `.env` files, APKs, databases, or device captures.
- Do not commit real personal messages, photos, or contact lists. Screenshots in `docs/readme/` are synthetic demonstration data.
- Commit messages: imperative subject under ~70 characters, then a short *why*.

## Device tests (optional)

Instrumented tests need an emulator. Pin `ANDROID_SERIAL` to one emulator and resolve that serial by AVD name. **Never run the suite on a personal phone.** The suite takes `ROLE_SMS` and installs/uninstalls the app. See [AGENTS.md](AGENTS.md).

## Style

Kotlin official style, 4-space indent. No `!!`, no empty `catch`, no `GlobalScope`, no `runBlocking` outside tests. Provider and disk I/O on `Dispatchers.IO`. User-visible strings in `res/values/strings.xml` except in `:core:rules`, which has no resources.
