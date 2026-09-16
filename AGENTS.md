# Agent notes (public)

This is the **public** application repository. There is no private spec vault in this tree. The README is the product description; this file is the operational short list.

## What this app is

An Android default-SMS app (`com.pinotrouge.messaging`) whose differentiator is user-authored, on-device spam filtering. Modules:

| Module | Role |
|---|---|
| `:core:rules` | Pure Kotlin/JVM rule engine. No `android.*` imports. |
| `:app` | Default SMS handler, UI, Room quarantine, WorkManager |

## Merge gate (required before a PR)

```bash
./gradlew :core:rules:test :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin assembleDebug
```

`:app:connectedDebugAndroidTest` is **not** in that gate and **not** in CI.

## 🚫 Never run anything on a personal phone

Instrumented tests take `ROLE_SMS`, intercept SMS, and uninstall the app when they finish. A physical phone is **never** a test target unless the repository owner names that device in a request.

## Pin `ANDROID_SERIAL` before any `adb` or Gradle device command

Ports move. Unpinned Gradle fans out to every attached device.

Resolve the serial by **AVD name**:

```bash
serial_for() {
  for s in $(adb devices | awk '/emulator-/{print $1}'); do
    n=$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')
    [ "$n" = "$1" ] && { echo "$s"; return; }
  done
}
export ANDROID_SERIAL=$(serial_for Medium_Phone_API_36.1)
```

The `tr -d '\r'` is required. `adb emu` replies with CRLF.

If you run the instrumented suite, cover **Android 13 and Android 16** emulators. Report pass and skip counts per AVD. A skipped test asserts nothing.

Two runners cannot share one AVD: the suite installs and uninstalls `com.pinotrouge.messaging`. Check the device is idle first.

Hand `ROLE_SMS` back to Google Messages when you are done. Turn TalkBack off after accessibility checks.

## Things that will get a PR rejected

- `dynamicColor` in `Theme.kt`
- Removing any of the four `ROLE_SMS` components
- An `android.*` import in `core/rules/`
- Any dependency that makes the app itself open network sockets
- Hard-coded hex or dp where a Pinot token exists
- Rewritten UI copy
- Real personal data in screenshots, fixtures, or logs

## Honesty

Say in the PR what you did not finish. A partial branch with clear edges is easier to review than one that claims to be complete and is not.
