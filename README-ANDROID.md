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
- Configurable partial CPU wake lock while sites run, plus an app battery-settings shortcut for screen-off hosting
- App-private file manager with import/export, text editing, ZIP extraction, and ZIP backup/restore
- Local request/error logs, per-site storage and 30-day served-payload totals, plus app RAM/CPU and free-storage samples
- Public GitHub repository deployment for prebuilt static folders, with commit-pinned releases, stopped-site updates, three local rollback snapshots, and deployment events

The API currently authenticates using a short-lived HttpOnly session cookie. The app keeps that cookie in memory for the current process; signing in again may be required after the app is closed or the 15-minute server session expires. Persistent refresh sessions are not part of the existing API yet.

On-device mode is independent of the API and stores metadata and website files in app-private storage. The Android server currently serves static files using GET and HEAD. A foreground service can hold a partial CPU wake lock while websites run; the dashboard exposes the battery trade-off and links to app settings. Android battery saver and Doze can still delay network access, so continuous hosting cannot be guaranteed on every device. GitHub deployment accepts a branch, tag, or commit from a public repository, resolves it to a commit SHA, and publishes a selected prebuilt folder containing `index.html`; stopped sites can be updated and rolled back from up to three local snapshots capped at 50 MiB each. It does not run a build. Private repository authorization, PHP/Node.js/Python runtimes, per-site databases, custom/public domains, public SSL, and cloud synchronization are not implemented in this Android slice. Wi-Fi sharing is optional and off by default.

The GitHub Actions `android` job runs Android unit tests and builds a debug APK with JDK 17 and Gradle 8.9. It uploads the artifact as `anb-hosting-android-debug`.

The workflow currently has no release signing configuration. A debug APK keeps the package ID `com.anbhosting.app`, but the repository does not contain a stable upload key; do not assume APKs produced on separate CI runners can update one another. A release update must use the existing signing key once it is available to the build securely.
