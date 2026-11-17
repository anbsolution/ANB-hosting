# ANB Hosting audit and development roadmap

## Snapshot

This audit describes the Android hosting work on `main` as of 2026-11-17. CI run `44026978552` verified commit `183d8ae` on GitHub `main`; the current working change adds HTTP byte ranges and is awaiting its own CI result. The Android package ID remains `com.anbhosting.app`.

“Added” means the implementation is present in source. It does not imply that the new Android code has passed its CI tests or a real-device check.

## Added in source

- NestJS email/password signup and login, Argon2id password hashing, JWT session cookies, CSRF checks, tenant-scoped API queries, and an initial admin membership role with role guards.
- PostgreSQL and Prisma models for users, tenants, memberships, websites, domains, and audit logs.
- A tenant-scoped website API with create, list, start, stop, restart, delete, and status operations.
- A Next.js dashboard with account, website, overview and activity views, plus a PWA app shell that does not cache API credentials or account data.
- Android login/signup against the existing API, workspace overview, website list, and website lifecycle actions.
- Android offline static hosting source: app-private website folders, an on-device SQLite catalog, localhost preview, optional read-only Wi-Fi access, a visible foreground service, and restart-on-next-app-launch recovery for sites left active.
- Android static HTTP server supports single byte ranges, suffix ranges, `HEAD` range metadata, and `416` responses for unsatisfiable ranges; integration tests cover transferred-byte accounting. This change is awaiting CI.
- Android local virtual-host source: every saved site's `<slug>.localhost` hostname routes to that active site's root for offline phone preview over IPv4/IPv6 loopback; Wi-Fi sharing keeps the existing phone-IP/path URL.
- Android local-domain source: each site can register and remove additional `.localhost` aliases in SQLite; the Host router resolves those aliases to the selected site's files, and the local dashboard exposes alias management and preview.
- Configurable Android partial CPU wake lock while active sites run, with an in-app battery setting and a shortcut to Android app battery settings.
- Android local website management source: SAF file import/export, delete/rename, small text-file editing, ZIP extraction, and ZIP backup/restore.
- Android local request/error events, per-site file/storage totals, 30-day successful response payload totals, app memory/CPU samples, and free-storage samples.
- Android public GitHub repository deployment source: select a branch, tag, or commit (or use the default), resolve it to a commit SHA, deploy to a new or stopped existing site, and roll back up to three saved file snapshots capped at 50 MiB each. ZIP and path limits are enforced; no build is run.
- GitHub Actions source for JavaScript workspace validation and an Android debug unit-test/APK build. The Android job runs `:app:testDebugUnitTest` before `:app:assembleDebug`.
- A separate GitHub Actions Python feasibility workflow source. It verifies fixed SHA-256 checksums for the official CPython 3.14.10 Android arm64 and x86_64 packages, then runs a small import/embedding smoke test in CPython's managed Android testbed; that workflow checks upstream feasibility independently from the ANB APK build.
- Android APK build source now stages those checksum-pinned CPython packages for arm64 and x86_64, compiles a JNI bridge against `libpython3.14.so` with the Android NDK, excludes CPython test/sample native extensions, packages the standard library and remaining native support libraries, and extracts them to app-private storage. The x86_64 emulator instrumentation source imports all 53 packaged production extensions. Other Android ABIs retain a small native library so the existing app remains installable, but they do not have Python. This does not run website code.
- Android has an unexported isolated runtime-worker service and an AIDL probe. Its passing instrumentation test checks that the worker has a different UID, can read an explicitly granted file descriptor, and cannot read a named ANB-private file by path. This validates the process boundary only; it does not execute Python or handle HTTP requests.
- Optional signed Android release build source: the app version is `2` with the same `com.anbhosting.app` ID, and CI requires the existing key plus its expected certificate SHA-256 fingerprint before it will upload a release APK; no key is configured yet.
- A Docker-based static-site engine and NGINX routing for a separate Linux host. The Docker site container has fixed CPU, memory, PID, capability, filesystem, and log limits.
- A script for private-CA HTTPS on the Linux-hosted LAN stack. Android trusts installed user CA certificates for API connections.

## Partially added

- **Android local hosting:** only static `GET` and `HEAD` are served. Phone preview and Wi-Fi sharing currently use HTTP; TLS is configured for the separate Docker/Nginx LAN host. A foreground service, configurable CPU wake lock, and relaunch recovery exist, but Doze, battery saver, OEM policies, or force-stop can still interrupt hosting; there is no boot-time recovery.
- **Website management:** file operations are local to Android app-private sites. The Docker-hosted website API still has no customer file upload, editor, archive, backup, or restore endpoints.
- **Roles and workspaces:** tenant isolation and an initial admin/user role foundation exist; there is no complete member invitation, role-management, or account administration workflow.
- **Domains and SSL:** the Android phone-local `<site-slug>.localhost` hostname and extra `.localhost` alias management are source-implemented for offline preview. The Android resolver returned both `::1` and `127.0.0.1`, and a loopback HTTP request to a temporary test server succeeded through the default hostname. These reserved aliases resolve to the current phone and are not reachable from other devices as those same names. The Prisma `Domain` model and Linux local-CA setup exist, but there is no Android custom-domain API, DNS ownership verification, registrar/provider integration, TLS on the phone, public certificate issuance, or automatic renewal.
- **Database support:** PostgreSQL stores ANB control-plane data. It is not a customer database feature and there is no MySQL-compatible server, per-site database/user management, import/export, or backup workflow.
- **Monitoring and limits:** Android now has local app/storage measurements and site request totals. It does not enforce per-site CPU/RAM/storage quotas. The Docker engine has container limits, but dashboard storage/bandwidth metrics are not wired to measured production data.
- **GitHub deployment:** public prebuilt static-folder deployment with branch/tag selection, commit-SHA pinning, stopped-site updates, and a three-snapshot local rollback history (50 MiB per backup) is implemented in source. Private-repository authorization, source builds, and cloud deployment history remain missing.
- **Dashboard parity:** the web dashboard and Android app provide the existing account/website workflows. Android does not yet have complete domains, databases, email, deployment, or hosting-plan modules; the UI is not yet a finished premium hosting console.
- **APK updates:** the application ID is stable and an optional signed release path is source-configured, but the repository has no stable release/upload key configured. CI currently produces only debug APKs; package ID alone does not make separately signed APKs update-compatible.
- **Python runtime:** source packages CPython 3.14.10 arm64 and x86_64, extracts its standard library, and passes an x86_64 emulator smoke test importing all 53 packaged production native extensions. Android app installs retain 32-bit ABI libraries without Python support. This code does not execute website code or provide a website runtime selector.

## Missing

- Packaged, on-device PHP and Node.js runtimes; Python website-code execution; runtime version selection; isolated per-site process controls; and useful build/runtime logs.
- A CPython Android package for 32-bit ABIs; the app can remain installable there, but the bundled Python check is limited to arm64 and x86_64.
- A real MySQL-compatible customer database service inside Android, with isolated databases/users, credentials, import/export, and backup/restore.
- Private GitHub repository authorization, on-device build steps, full build/deploy history, and cloud deployment history.
- Custom-domain/subdomain management, DNS configuration and verification, public Let's Encrypt issuance, and renewal.
- Optional synchronization to a self-hosted ANB endpoint, with conflict handling and user-controlled data scope.
- Functional Android modules for domains, databases, and email services, and complete web/mobile feature parity.
- Hosting plans/billing and an internet-reachable public hosting deployment. No public hosting should be exposed until real server capacity, DNS, ingress, TLS, operations, and abuse controls exist.

## Not yet tested

- The new byte-range behavior and its server integration tests have not yet run in GitHub Actions for the current working change.
- The wake-lock acquire/release lifecycle has not been exercised on a physical Android device.
- Browser preview, phone-hostname routing, Wi-Fi sharing, screen-off serving, and battery behavior have not been verified on a physical phone or across OEM power policies.
- Version 2 release signing, the certificate-fingerprint gate, and signed APK update compatibility have not been verified. The release signing key and fingerprint are not configured in GitHub Actions.
- No APK has been installed or tested on a physical Android device. CI builds a debug APK; it does not produce a signed release without the existing signing secrets.
- The 32-bit app ABI placeholder libraries have not been installed on a 32-bit Android device.
- Android local domain alias and file-management features have CI unit-test coverage, but have not been reviewed on a physical phone.
- No full Docker Compose, NGINX, DNS, ACME, private-repository GitHub deployment, or live multi-device hosting test was performed in this audit.
- The monorepo lint, formatting, typecheck, and JavaScript test commands passed locally. API tests: 2 passed; hosting-engine tests: 2 passed. `npm run build` could not finish on Android because Next.js requested a nonexistent `@next/swc-android-arm64` binary. This device limitation is not an Android CI build result.

## Target architecture

```mermaid
flowchart TD
  UI[Compose hosting dashboard] --> Store[SQLite metadata, events, traffic]
  UI --> Files[App-private site storage and file manager]
  Service[Android foreground hosting service] --> HTTP[Local static HTTP server]
  HTTP --> Files
  HTTP --> Runtime[Future runtime supervisor]
  Runtime --> Isolated[Isolated per-site runtimes; AIDL boundary probe exists]
  Runtime --> DB[Future per-site MySQL-compatible service]
  Cloud[Optional self-hosted ANB sync/API] <--> Sync[Opt-in sync and conflict resolver]
  Sync <--> Store
  Sync <--> Files
```

The current Android path is the SQLite/store, file manager, foreground service, and static HTTP server. Runtime and database boxes are future work. The foreground supervisor will own the listener while an Android isolated worker handles dynamic site code over a narrow IPC contract. The worker AIDL currently exposes only an isolation probe; it is not yet connected to CPython or HTTP. The app must pass explicit file descriptors for the selected site's root and runtime files; the worker must not receive account/session data. The instrumentation source uses separate isolated service instances per site on API 29 and later; Android 8/9 static hosting remains available without per-site workers. These paths are not yet tested. Before enabling dynamic code, demonstrate descriptor-scoped reads, sibling-site and app-private denial, CPython loading from the worker, HTTP body/response streaming, process recovery, and bounded CPU/memory/storage on devices.

The existing web/cloud path remains separate: web or Android client → LAN NGINX/API → PostgreSQL and the private hosting engine → static Docker site container. This stack runs on an external Linux host; it is not the Android-local server.

## Phase-wise roadmap

### Phase 1 — Stabilize offline static hosting

- Run the Android CI tests and debug APK build for all local commits after `e074683`; fix every compile/test failure.
- Exercise create/start/preview/stop, Wi-Fi rebind, notification denial, process death/relaunch, archive safety, backup restore, and storage accounting on Android devices.
- Improve foreground-service and battery guidance using supported Android APIs, while showing when the OS has stopped hosting.
- Verify CPU wake-lock acquisition/release on supported Android versions and measure screen-off serving, battery use, Doze behavior, and user battery-setting changes across device vendors.
- Keep local HTTP sharing off by default and clearly distinguish phone HTTP preview from the separate LAN host's HTTPS setup.

### Phase 2 — Runtime foundation and Python feasibility

- Define a per-site runtime contract for install/version, start/stop/restart, health, logs, and enforceable resource limits.
- Run the CPython Android feasibility workflow against python.org's checksum-verified arm64 and x86_64 packages. Package `libpython` and its standard library in ANB through GitHub Actions, then import all packaged production native extensions on an emulator. Android uses embedded `libpython`; it does not provide a system Python executable.
- Keep the Kotlin HTTP server as the listener and route Python requests through an explicit worker IPC contract. On API 29+, bind one isolated service instance per site and pass only the runtime and that site's directory descriptors. The worker must not receive ANB session credentials or unrestricted access to other sites.
- Prove worker isolation, scoped file access, request body/response streaming, process recovery, and bounded CPU/memory/storage before adding a runtime selector or running imported Python code.
- Add Python selection only after the packaged runtime, a real HTTP app, lifecycle, useful logs, stop/restart, and device tests pass together.

### Phase 3 — Node.js and PHP feasibility

- Evaluate each runtime against the same package, IPC, isolation, storage, battery, ABI, and release-size gates before promising support.
- Node.js documents Android as unsupported and says it has no Android testing in its current CI. PHP's installation manual says cross-compiling with the Android toolchain is currently unsupported. Treat both as downstream porting projects, not routine runtime packaging.
- Build candidate artifacts only in GitHub Actions, run upstream/runtime smoke tests where available, and test on supported Android devices. Do not download interpreters when a site starts.
- Expose a runtime/version only after its actual Android artifact, per-site lifecycle, logs, limits, restart behavior, and file isolation work end to end.

### Phase 4 — MySQL-compatible databases

- Prove a bundled Android-compatible MySQL/MariaDB server can run within phone storage, RAM, CPU, and battery limits.
- Keep database sockets private to the phone; add per-site database/user creation, credential rotation, import/export, backup/restore, quotas, and logs.
- If a safe, maintainable Android server cannot be shipped, expose the limitation instead of labeling SQLite as MySQL.

### Phase 5 — GitHub deployment and build history

- Add release builds, a full deployment timeline, and cloud deployment history to the implemented public prebuilt-static deployment.
- Add private repositories only with a proper OAuth/app authorization flow.
- Build using supported on-device runtimes, stage releases atomically, and keep detailed build/deploy logs and rollback history.
- Do not store personal access tokens in logs or plain-text preferences.

### Phase 6 — Domains and certificates

- Exercise the local `.localhost` alias manager, database migration, and Host routing with Android CI and a real device.
- Implement real domain records, host routing, ownership challenges, and DNS verification with documented provider adapters.
- For phone-only local hosting, provide local-network naming/private certificate options and explain that public DNS cannot route through carrier NAT or a private Wi-Fi address.
- Add Let's Encrypt issuance and renewal only for a deployment with working public ingress or a configured DNS-01 provider and securely stored credentials.

### Phase 7 — Dashboard parity and optional sync

- Add mobile and web screens only alongside working APIs for files, runtimes, databases, domains, logs, and emails.
- Add opt-in sync to a self-hosted ANB endpoint with per-category selection, encryption in transit, conflict handling, resumable transfers, and offline-first behavior.
- Add resource plans/billing only when the product has a real metering and billing service.

### Phase 8 — Public hosting readiness

- Require actual internet-reachable infrastructure, durable storage, DNS, ingress, certificate automation, tenant isolation, monitoring, backups, incident response, and abuse controls.
- Until those prerequisites exist, keep public hosting unavailable and continue to target offline phone hosting plus the user's self-managed LAN server.

## Release compatibility gate

Before publishing an update APK, identify the signing key and SHA-256 certificate fingerprint used by the existing installed/released app. Store them only in GitHub Actions secrets and build a signed release using that key. CI must compare the APK signer fingerprint with the expected fingerprint before uploading. If no prior release key exists, create and back it up before first release. A new debug key or a different signing key will not update an app installed with the old signature.

## Runtime feasibility references

- CPython Android embedding and packaging: <https://docs.python.org/3.14/using/android.html> and <https://github.com/python/cpython/blob/3.14/Android/README.md>.
- Android isolated services and per-instance binding: <https://developer.android.com/guide/topics/manifest/service-element#isolated_process> and <https://developer.android.com/reference/android/content/Context#bindIsolatedService(android.content.Intent,int,java.lang.String,java.util.concurrent.Executor,android.content.ServiceConnection)>.
- Cross-process file-descriptor access: <https://developer.android.com/reference/android/os/ParcelFileDescriptor>.
- Node.js Android build/support status: <https://github.com/nodejs/node/blob/main/BUILDING.md#android>.
- PHP Android toolchain cross-compilation status: <https://www.php.net/manual/en/install.unix.source.php>.
- Android isolated service process model: <https://developer.android.com/guide/topics/manifest/service-element#isolated_process>.
- Reserved `.localhost` hostname behavior: <https://www.rfc-editor.org/rfc/rfc6761.html#section-6.3>.
