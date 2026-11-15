# ANB Hosting Native Android app

This is the first native Android client slice for the existing ANB Hosting platform.

- Kotlin + Jetpack Compose
- Uses the existing NestJS API at `/api/v1`
- Email login and registration
- Workspace summary and website listing
- Website start, stop and restart actions
- HTTPS-only connections; local deployments require installing the trusted local CA certificate on the Android device

The API currently authenticates using a short-lived HttpOnly session cookie. The app keeps that cookie in memory for the current process; signing in again may be required after the app is closed or the 15-minute server session expires. Persistent refresh sessions are not part of the existing API yet.

Build from the repository root on a machine with Android SDK and Gradle available:

```sh
gradle -p android :app:assembleDebug
```

GitHub Actions builds a debug APK and uploads it as `anb-hosting-android-debug` when CI runs.
