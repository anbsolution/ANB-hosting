# ANB Hosting Native Android app

This is the first native Android client slice for the existing ANB Hosting platform.

- Kotlin + Jetpack Compose
- Uses the existing NestJS API at `/api/v1`
- Email login and registration
- Workspace summary and website listing
- Website start, stop and restart actions
- HTTPS-only connections; local deployments require installing the trusted local CA certificate on the Android device
- On-device offline mode with an app-private SQLite catalog and website files
- Local static HTTP server with per-site `<slug>.localhost` phone-only subdomains over loopback; optional read-only Wi-Fi sharing binds to the phone's Wi-Fi IPv4 address and keeps the path-based URL
- User-visible foreground service for hosting lifecycle
- Configurable partial CPU wake lock while sites run, plus an app battery-settings shortcut for screen-off hosting
- App-private file manager with import/export, text editing, ZIP extraction, and ZIP backup/restore
- Per-site offline hostname aliases under the reserved `.localhost` domain, stored in the local database and routed to the selected site's files
- Local request/error logs, per-site storage and 30-day served-payload totals, plus app RAM/CPU and free-storage samples
- Public GitHub repository deployment for prebuilt static folders, with commit-pinned releases, stopped-site updates, three local rollback snapshots, and deployment events
- Checksum-pinned CPython 3.14.10 packages for Android arm64 and x86_64 are staged into the APK through NDK/CMake; test-only extension libraries are excluded, and instrumentation checks all 53 packaged production extensions

The API currently authenticates using a short-lived HttpOnly session cookie. The app keeps that cookie in memory for the current process; signing in again may be required after the app is closed or the 15-minute server session expires. Persistent refresh sessions are not part of the existing API yet.

On-device mode is independent of the API and stores metadata and website files in app-private storage. The Android server currently serves static files using GET and HEAD. Each site gets a default `<slug>.localhost` hostname and can have additional `.localhost` aliases routed to the same site's root; reserved `.localhost` names resolve to the current phone and are not public domains. A foreground service can hold a partial CPU wake lock while websites run; the dashboard exposes the battery trade-off and links to app settings. Android battery saver and Doze can still delay network access, so continuous hosting cannot be guaranteed on every device. GitHub deployment accepts a branch, tag, or commit from a public repository, resolves it to a commit SHA, and publishes a selected prebuilt folder containing `index.html`; stopped sites can be updated and rolled back from up to three local snapshots capped at 50 MiB each. It does not run a build. Private repository authorization, PHP/Node.js/Python runtimes, per-site databases, custom/public domain verification, phone TLS, public SSL, and cloud synchronization are not implemented in this Android slice. Wi-Fi sharing is optional and off by default.

The GitHub Actions `android` job runs Android unit tests and builds a debug APK with JDK 17 and Gradle 8.9. It uploads the artifact as `anb-hosting-android-debug`.

Android CI is configured to stage the official CPython 3.14.10 Android packages for arm64 and x86_64 only after checking pinned SHA-256 values, package `libpython`, its Android support libraries and the standard library, then import all 53 packaged production extensions in an x86_64 Android emulator. A Python unit test checks that only CPython test/sample native modules are filtered out. An isolated worker service and AIDL probe are now present; their Android test source checks the separate UID and access through an explicitly granted directory descriptor. These tests have not passed in ANB CI yet. The app build retains arm64, armeabi-v7a, x86_64 and x86 installation support; 32-bit builds include a native placeholder and do not provide Python. This source has not passed the ANB CI run yet. Python site code is not executed: the app still needs CPython initialization in the worker, HTTP request handling, lifecycle controls, logs and enforceable resource limits before Python website hosting can be enabled. The separate `Android Python runtime feasibility` workflow continues to test the upstream CPython package in its managed Android testbed.

The workflow can build and upload a signed release APK when these secrets are configured: `ANB_RELEASE_KEYSTORE_BASE64`, `ANB_RELEASE_STORE_PASSWORD`, `ANB_RELEASE_KEY_ALIAS`, `ANB_RELEASE_KEY_PASSWORD`, and `ANB_RELEASE_CERT_SHA256`. Use the existing app signing key and its SHA-256 certificate fingerprint. CI verifies the APK fingerprint before uploading it. No signing key is configured in the repository yet, so CI skips the release APK and builds only the debug artifact. A debug APK keeps the package ID `com.anbhosting.app`, but APKs signed with different keys cannot update one another. The app version code is now 2; an installed version 1 still requires the matching signing key to accept the update.
