# ANB Hosting Platform

Phase 1 and the initial Phase 2 website-hosting slice for a multi-tenant web-hosting control panel, configured for offline runtime on a trusted local network. The repository is an npm-workspaces monorepo with a Next.js dashboard, a NestJS REST API, a Prisma/PostgreSQL data layer, and a restricted hosting-engine service. File management, PHP/runtime selection, registrar integrations, public SSL issuance, and production VPS deployment are not enabled yet.

## Repository layout

```text
apps/
  api/                 NestJS API (auth, health, dashboard, website lifecycle)
  dashboard/           Next.js responsive control panel
packages/
  database/            Prisma schema and database scripts
services/
  hosting-engine/      Private container lifecycle API and static-site image
infrastructure/
  docker/              Container build files
  nginx/               Dashboard/API proxy and generated site routing
.github/workflows/     Continuous integration
docker-compose.yml     Local PostgreSQL + API + dashboard + engine + NGINX
```

## Run on a local Wi-Fi network

1. Prepare a local always-on Linux computer running Docker Engine and connected to the same Wi-Fi/LAN as the phones or computers that need access. This LAN-only mode is not suitable for Codespaces or a public VPS. Docker Desktop may translate client IPs, preventing the LAN allowlist from recognizing them.
2. In the terminal, create a local environment file and replace the example values:

   ```sh
   cp .env.example .env
   ```

   Generate independent values with `openssl rand -hex 32`; use separate values for `POSTGRES_PASSWORD`, `JWT_SECRET`, and `ENGINE_TOKEN`. Set `LAN_SERVER_IP` to the host's reserved Wi-Fi IPv4 address and `LAN_ALLOWED_CIDR` to the router's client subnet in CIDR notation (for example, `192.168.1.0/24`); neither example value may match your network. Use the network address for the CIDR, not the server's individual IP. Hex avoids URL-encoding issues in the local database URL. Keep `.env` private; it is ignored by Git.
3. Reserve a stable LAN IP for the host, then create a local HTTPS certificate for it:

   ```sh
   sh infrastructure/scripts/generate-local-certs.sh 192.168.1.50
   ```

   Replace the example address with the same `LAN_SERVER_IP` set in `.env`. Copy `infrastructure/local-certs/local-ca.crt` (not `local-ca.key`) to each Android device and install it as a CA certificate in Android security settings. Trust this private CA only on devices you control; its private key stays on the host and the certificate files are Git-ignored.
4. Install workspace dependencies and generate the Prisma client:

   ```sh
   npm install
   npm run db:generate
   ```
5. Start the full local stack:

   ```sh
   docker compose up --build
   ```

   Only NGINX is published by Compose; it rejects requests outside `LAN_ALLOWED_CIDR`. HTTP on port **8080** redirects to HTTPS on **8443**. The API, database, and engine API remain on an internal Docker network, and the app network has no internet egress. Compose initializes the development database schema with `prisma db push`; use reviewed, versioned migrations for production.
6. From a device connected to the same Wi-Fi, open `https://<server-lan-ip>:8443`, create an account, and use **Websites** to provision the platform's starter static page. In Chrome, use **Install app** / **Add to Home screen**. The **Preview website** link opens the site through the same LAN HTTPS origin. Customer uploads and repository deployments are not implemented yet. No sample account or seeded credentials are included.

The first dependency/image download and `docker compose up --build` need internet. Once built, start the stack without rebuilding using `docker compose up`; runtime services can operate without internet. Keep the host computer on the Wi-Fi network; its LAN IP should be stable or DHCP-reserved. The local `.test` hostnames do not change DNS; use the path-based preview links. LAN HTTPS uses the installed private CA; public custom hostnames and publicly trusted certificates still require internet and a real DNS/ACME integration.

The dashboard is a PWA; it caches the static app shell and build assets without caching API responses, credentials, or account data. It requires the trusted local HTTPS certificate above for installation and service-worker support. The cached shell can open without internet, but account actions still require the local host and Wi-Fi connection. No Android APK is built.

Website provisioning requires the complete Compose stack because it depends on the isolated hosting engine and NGINX. The stack is deliberately not exposed through a Codespaces port forward or public VPS in this local-only configuration.

## Workspace commands

| Command | Purpose |
| --- | --- |
| `npm install` | Install all workspace dependencies |
| `npm run db:generate` | Generate Prisma Client |
| `npm run db:push` | Apply the current Prisma schema to a development database |
| `npm run dev:api` | Start the API in watch mode |
| `npm run dev:dashboard` | Start the dashboard |
| `npm run lint` | Run ESLint across the workspaces |
| `npm run format:check` | Check formatting with Prettier |
| `npm run typecheck` | Type-check the API, dashboard, and hosting engine |
| `npm run test` | Run available workspace tests |
| `npm run build` | Build all workspaces |

## Foundation behavior and security

- Passwords are hashed with Argon2id. Authentication uses a short-lived JWT in an `HttpOnly`, `SameSite=Strict` cookie; browser JavaScript never receives the token. State-changing auth requests require a matching double-submit CSRF cookie and header.
- API requests are validated, rate-limited, and CORS is restricted to configured web origins. Tenant-scoped dashboard queries use the tenant ID in the signed session.
- Registration assigns the initial workspace administrator role. The schema and role guard provide an authorization foundation for later admin-only operations.
- Static website provisioning uses a fixed platform image, UUID-derived container names, validated hostnames, per-site internal Docker networks, read-only root filesystems, dropped Linux capabilities, and memory/CPU/PID/log limits. Lifecycle commands are an allowlisted internal API; customer input is never passed to a shell or used as a container command/image.
- Provisioned containers currently serve a platform starter page; customer file uploads, arbitrary static-site contents, PHP, Git deployments, and build workflows are not implemented.
- The engine API has no published port. The Docker socket is mounted only into the private hosting-engine container, never into the dashboard/API/NGINX. A Docker socket grants host-level control if the engine is compromised: use only on a trusted isolated development/hosting node; production should use a dedicated rootless runtime or separately isolated host and reviewed access policy.
- NGINX binds only to `LAN_SERVER_IP` and accepts loopback plus the configured `LAN_ALLOWED_CIDR`; this filters by source IP subnet, not Wi-Fi SSID, so wired devices on that subnet can also connect. The Compose control-plane network is internal and has no internet egress. Do not forward ports 8080 or 8443 from the router.
- Dashboard counts come from PostgreSQL. Storage and bandwidth are shown as not yet measured instead of fabricated metrics.
- Registrar/DNS changes, SSL issuance, file/database operations, backups, and GitHub deployments still require later phases and real provider integrations.

## Configuration

See [.env.example](./.env.example). Never commit `.env`, production secrets, customer credentials, or database dumps. Use a secret manager or the deployment environment for production values. The Compose credentials are for local development only.

## Next steps: Phase 3

1. Implement DNS provider interfaces and verification challenges for public deployments; do not imply registrar or DNS changes without an actual integration.
2. Add tenant-scoped custom-domain and subdomain management with ownership verification.
3. Add publicly trusted Let's Encrypt issuance/renewal behind a supported ACME integration.
4. Add integration tests for DNS verification, host routing, certificate renewal, and tenant boundaries.
5. Replace development `prisma db push` with reviewed migrations before production.

No public VPS deployment is configured by this repository.
