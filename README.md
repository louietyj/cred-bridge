# cred-bridge

The phone side of [request-credential](https://github.com/louietyj/claude-skills/tree/main/request-credential):
Claude asks for a site's login, a notification appears, and a form opens that
reports itself to Android autofill as that site (`ViewStructure.setWebDomain`).
The password manager fills it; Send (behind a fingerprint, face or PIN) seals
the login to the request-text link Claude opened and posts it there.

The app is labelled "Credential Request". It needs Android 14+.

Opening it checks the pairing with the relay (and hands over the current FCM
token) and lists the requests still waiting, so a request whose notification
never came can be answered from there. The pairing box appears only while the
relay doesn't know the phone.

A push wakes the app with its network still blocked: Android cuts a cached
app's network and lifts the block a moment after the push arrives. So the
push handler retries its fetch for a few seconds, and if that still fails it
posts a plain "Claude is asking for a login" notification; opening that
fetches it in the foreground.

## Build

Needs `app/google-services.json` from the `cred-bridge` Firebase project
(`firebase apps:sdkconfig ANDROID <app id> --project cred-bridge`). The relay
URL is `credBridge.workerUrl` in `gradle.properties`. Builds must be signed
with the same debug keystore as the installed app, or Android refuses the
update and the phone has to re-pair.

```bash
./gradlew installDebug
./gradlew testDebugUnitTest   # Seal against a real request-text server, from ../claude-skills
```

Then open the app and enter the code from `request-credential pair`.

## Testing without the relay

Debug builds let `RequestActivity` submit straight to a local request-text:

```bash
REQUEST_TEXT_HOME=/tmp/rt node ../claude-skills/request-text/bin/request-text.mjs open --local \
  --title example.com --field username:line --field password:secret
adb reverse tcp:<port> tcp:<port>
adb shell am start -n me.louietyj.credbridge/.RequestActivity \
  --es debug_url http://127.0.0.1:<port>/<token> --es debug_site example.com
```

## Notes

- `Seal` is byte-compatible with request-text's page; the unit test proves it
  against the real server.
- No `record`s: AGP 9.2's D8 emits a reference to `com.android.tools.r8.RecordTag`
  and leaves the class out of the APK, which crashes on launch.
