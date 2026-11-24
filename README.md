# ScreenlyyID Android example

An Android app **and the back end it needs**, showing how to add ScreenlyyID identity verification: create a session, open the hosted capture page in a `WebView`, listen for the completion event, and read the result.

- Docs: https://www.screenlyyid.com/docs/integrations/mobile-app/
- Recipe: https://www.screenlyyid.com/docs/recipes/android/
- AI quickstart: https://www.screenlyyid.com/docs/ai-quickstart/
- All developer docs as Markdown: https://www.screenlyyid.com/docs/llms-full.txt
- The same example for iOS: https://github.com/ScreenlyyID/ios-sdk-example

This is an example, not a library: copy the parts you need. Kotlin, no third-party dependencies (only the Android framework). Android 7.0 (API 24) or later; built with Android Gradle Plugin 8.5 and JDK 17.

## The structure: every ScreenlyyID API call is made by a back end

```
Android app (app/)               your back end (backend/)                 ScreenlyyID
------------------------         ----------------------------------       ------------------------------------
POST /verifications        -->   POST /api/v1/instance/create        -->  x-api-key: <key>
                           <--   { CorrelationId, webEnrolmentUrl }   <--
  <-- { verificationId, webEnrolmentUrl }
open webEnrolmentUrl in a WebView ... the person captures the document and a selfie ...
IDV_SESSION_COMPLETE event
GET /verifications/:id     -->   GET /api/v1/document/result         -->  x-api-key: <key>
                                                                          x-correlation-id: <CorrelationId>
  <-- { status, result }   <--
```

- **The API key and the `CorrelationId` live only in `backend/`.** The app contains neither. Anything inside an app can be extracted, so an API key there is a leaked key.
- **The app calls only your back end** (`DemoConfig.BACKEND_URL`). It never calls `api.screenlyyid.com`.
- The back end is deliberately tiny and has no dependencies (Node 18 or later). It is the same back end as in the iOS example. Copy its two routes into whatever server you already run.

## Run it

1. **Get a test API key.** Register at https://admin.screenlyyid.com/register, open **Integrations** in the dashboard and create a key in the test environment. Test keys start with `test_`.
2. **Start the back end:**
   ```bash
   cd backend
   SCREENLYYID_API_KEY=test_xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx node server.mjs
   ```
   `npm test` runs its tests against a fake ScreenlyyID API (no key needed).
3. **Point the app at it.** Set `DemoConfig.BACKEND_URL` in `app/src/main/java/com/screenlyyid/democamera/Backend.kt`:
   - **Emulator:** `http://10.0.2.2:3000` (the default). `10.0.2.2` is the emulator's name for your computer.
   - **Real phone over USB:** run `adb reverse tcp:3000 tcp:3000`, then use `http://localhost:3000`.
4. **Run the app.** Open the folder in Android Studio and run the `app` configuration, or from a terminal:
   ```bash
   ./gradlew installDebug
   ```

The app's `network_security_config.xml` allows plain HTTP to `10.0.2.2` and `localhost` for this demo only. A real back end uses HTTPS, so delete that file and the `android:networkSecurityConfig` line in `AndroidManifest.xml`.

> **Demo only:** the back end has no authentication of its own. A real back end must check who is asking before it creates or reads a verification, store the `CorrelationId` in a database (this one keeps it in memory), and decide what is safe to send back to the app.

## What happens, step by step

| Step | Where | What happens |
|---|---|---|
| 1. Create a session | `backend/server.mjs` (called from `Backend.kt`) | The back end sends `POST /api/v1/instance/create` with the headers `x-api-key` and `Content-Type: application/json` and the body `{"GlobalScreening": ["idv"], "CustomerIntelligence": []}`. A `200` response has two properties, spelled exactly like this: `CorrelationId` and `webEnrolmentUrl`. The back end keeps the `CorrelationId` and sends the app only a `verificationId` and the `webEnrolmentUrl`. `idv` (identity verification) must be activated on your account. A missing or bad key returns `401` and a bad product code returns `400`, both with a problem-details JSON body. |
| 2. Open the WebView | `MainActivity.kt`, `VerificationActivity.kt` | The app asks for camera permission, then loads `webEnrolmentUrl` in a `WebView`. The second button adds `?skipIntro=true` to go straight to image capture. |
| 3. Listen for events | `VerificationActivity.kt` | The hosted page sends `postMessage` events shaped `{ "eventName": "", "isError": false, "error": "" }`. A small script forwards them to a JavaScript bridge. `IDV_SESSION_COMPLETE` means the document and selfie were collected. `ERROR` is a fatal error while processing the images. |
| 4. Get the result | `backend/server.mjs` (asked from `ResultActivity.kt`) | The app asks the back end `GET /verifications/:id`. The back end calls `GET /api/v1/document/result` with the headers `x-api-key` and `x-correlation-id`, and answers `pending` if it is asked too early. `IDV_SESSION_COMPLETE` is sent only after the results are stored, so one request after the event is enough: there is no polling. It can also call `GET /api/v1/document/data` (the data read from the ID): that holds personal data, so keep it on the server and send the app only what it needs. |

The `IDV_SESSION_COMPLETE` event tells the app when to ask for the result. You can also see every verification in the dashboard under **Verifications**.

## The Android WebView details

Two things differ from iOS, and they are the usual reasons capture "does nothing" on Android.

- **The camera must be granted to the page explicitly.** Declaring `CAMERA` in the manifest and asking the user for it is not enough: the page then asks the WebView for the camera, and the WebView only gives it to the page if your `WebChromeClient.onPermissionRequest` grants it. `VerificationActivity` does this, only for ScreenlyyID pages, and only for the camera and microphone the app itself holds.
- **There is no built-in way to receive `postMessage` events.** The hosted page sends them with `window.parent.postMessage`, and inside a WebView there is no outer page to receive them. The example registers a JavaScript interface (`androidListener`) and injects a one-line `message` listener once the page has loaded.

Other things the example does:

- **Camera permission before the page loads.** `MainActivity` asks for `CAMERA` first, and if the user chose "Don't ask again" it sends them to the app's Settings page instead of loading a page that cannot capture.
- **`mediaPlaybackRequiresUserGesture = false`** so the camera preview plays inside the page without a tap.
- **Only ScreenlyyID pages load.** `shouldOverrideUrlLoading` refuses to navigate anywhere outside `screenlyyid.com`, and the bridge is only wired up on ScreenlyyID pages.
- **`RECORD_AUDIO`** is declared and requested only if the capture page asks for audio. Remove it from the manifest, and the audio branch in `VerificationActivity`, once you have confirmed your flow never needs the microphone.

## Files

- `backend/server.mjs`: the example back end. `backend/test/server.test.mjs`: its tests.
- `app/src/main/java/com/screenlyyid/democamera/Backend.kt`: configuration, and the app's calls to your back end.
- `app/src/main/java/com/screenlyyid/democamera/MainActivity.kt`: camera permission and the two buttons.
- `app/src/main/java/com/screenlyyid/democamera/VerificationActivity.kt`: the `WebView`, the camera grant, the event bridge, completion and error handling.
- `app/src/main/java/com/screenlyyid/democamera/ResultActivity.kt`: asks the back end for the result once and shows it: the outcome (approved, needs review or rejected, from `Decision.Level`, `LivenessResult.Result` and `FaceMatchResult.IsMatch`) and the level of each data point. None of those fields holds personal data. In a real app your back end applies that rule and sends the app only the outcome.
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`: permissions, and the demo-only HTTP allowance.
