# ANB Hosting Native Android app

This is the first native Android client slice for the existing ANB Hosting platform.

- Kotlin + Jetpack Compose
- Uses the existing NestJS API at `/api/v1`
- Email login and registration
- Workspace summary and website listing
- Website start, stop and restart actions
- HTTPS-only connections; local deployments require installing the trusted local CA certificate on the Android device
- On-device offline mode with an app-private SQLite catalog and website files
- Local static HTTP server with localhost preview; optional read-only Wi-Fi sharing binds to the phone's Wi-Fi IPv4 address
- User-visible foreground service for hosting lifecycle
- App-private file manager with import/export, text editing, ZIP extraction, and ZIP backup/restore

The API currently authenticates using a short-lived HttpOnly session cookie. The app keeps that cookie in memory for the current process; signing in again may be required after the app is closed or the 15-minute server session expires. Persistent refresh sessions are not part of the existing API yet.

On-device mode is independent of the API and stores metadata and website files in app-private storage. The Android server currently serves static files using GET and HEAD. PHP, Node.js and Python runtimes, per-site databases, public domains, public SSL, GitHub deployment and cloud synchronization are not implemented in this Android slice. Wi-Fi sharing is optional and off by default; Android battery policy and the phone's network state can still affect a long-running service.

The GitHub Actions `android` job runs Android unit tests and builds a debug APK with JDK 17 and Gradle 8.9. It uploads the artifact as `anb-hosting-android-debug`.

The workflow currently has no release signing configuration. A debug APK keeps the package ID `com.anbhosting.app`, but the repository does not contain a stable upload key; do not assume APKs produced on separate CI runners can update one another. A release update must use the existing signing key once it is available to the build securely.
