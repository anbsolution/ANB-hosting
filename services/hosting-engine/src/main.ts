import { createServer, IncomingMessage, ServerResponse } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { SiteManager, isValidHostname } from './site-manager';

const token = process.env.ENGINE_TOKEN;
const port = Number(process.env.PORT ?? 3010);
const domainSuffix = validateDomainSuffix(process.env.WEBSITE_DOMAIN_SUFFIX);

if (!token || token.length < 32)
  throw new Error('ENGINE_TOKEN must contain at least 32 characters');
if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error('PORT is invalid');

const manager = new SiteManager({
  socketPath: process.env.DOCKER_SOCKET_PATH ?? '/var/run/docker.sock',
  siteImage: process.env.SITE_IMAGE ?? 'anb-hosting-site:0.1.0',
  nginxContainer: process.env.NGINX_CONTAINER_NAME ?? 'anb-hosting-nginx',
  configDirectory: process.env.HOSTING_CONFIG_DIR ?? '/var/lib/hosting-engine',
  domainSuffix,
});

const server = createServer(async (request, response) => {
  try {
    await handleRequest(request, response);
  } catch (error) {
    if (error instanceof RequestError) {
      sendJson(response, error.status, { message: error.message });
      return;
    }
    console.error(
      'Hosting engine operation failed',
      error instanceof Error ? error.message : 'Unknown error',
    );
    sendJson(response, 500, { message: 'Hosting engine operation failed' });
  }
});

async function handleRequest(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const url = new URL(request.url ?? '/', 'http://hosting-engine');
  if (request.method === 'GET' && url.pathname === '/health') {
    sendJson(response, 200, { status: 'ok' });
    return;
  }
  if (!authorized(request.headers.authorization)) {
    sendJson(response, 401, { message: 'Unauthorized' });
    return;
  }

  if (request.method === 'POST' && url.pathname === '/v1/sites') {
    const body = await readJson(request);
    if (
      !body ||
      typeof body !== 'object' ||
      Array.isArray(body) ||
      Object.keys(body).some((key) => key !== 'id' && key !== 'hostname')
    ) {
      sendJson(response, 400, { message: 'Invalid site request' });
      return;
    }
    const { id, hostname } = body as { id?: unknown; hostname?: unknown };
    if (
      typeof id !== 'string' ||
      typeof hostname !== 'string' ||
      !isSiteId(id) ||
      !isValidHostname(hostname, domainSuffix)
    ) {
      sendJson(response, 400, { message: 'Invalid site request' });
      return;
    }
    sendJson(response, 201, await manager.provision(id, hostname));
    return;
  }

  const siteRoute = /^\/v1\/sites\/([0-9a-f-]{36})(?:\/(start|stop|restart))?$/.exec(url.pathname);
  if (!siteRoute) {
    sendJson(response, 404, { message: 'Not found' });
    return;
  }

  const id = siteRoute[1];
  const action = siteRoute[2];
  if (!id) {
    sendJson(response, 404, { message: 'Not found' });
    return;
  }
  if (request.method === 'GET' && !action) {
    sendJson(response, 200, await manager.status(id));
    return;
  }
  if (request.method === 'DELETE' && !action) {
    await manager.remove(id);
    response.writeHead(204).end();
    return;
  }
  if (request.method === 'POST' && action) {
    if (action === 'start') {
      const body = await readJson(request);
      if (
        !body ||
        typeof body !== 'object' ||
        Array.isArray(body) ||
        Object.keys(body).some((key) => key !== 'hostname')
      ) {
        sendJson(response, 400, { message: 'Invalid site request' });
        return;
      }
      const { hostname } = body as { hostname?: unknown };
      if (typeof hostname !== 'string' || !isValidHostname(hostname, domainSuffix)) {
        sendJson(response, 400, { message: 'Invalid site request' });
        return;
      }
      await manager.start(id, hostname);
    }
    if (action === 'stop') await manager.stop(id);
    if (action === 'restart') await manager.restart(id);
    response.writeHead(204).end();
    return;
  }
  sendJson(response, 405, { message: 'Method not allowed' });
}

function isSiteId(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function validateDomainSuffix(value: string | undefined): string {
  const suffix = value?.toLowerCase();
  if (
    !suffix ||
    !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(
      suffix,
    )
  ) {
    throw new Error('WEBSITE_DOMAIN_SUFFIX is invalid');
  }
  return suffix;
}

function authorized(header: string | undefined): boolean {
  const expected = Buffer.from(`Bearer ${token}`);
  const supplied = Buffer.from(header ?? '');
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

async function readJson(request: IncomingMessage): Promise<unknown> {
  if (!request.headers['content-type']?.startsWith('application/json')) {
    throw new RequestError(415, 'Expected JSON content');
  }
  const chunks: Buffer[] = [];
  let length = 0;
  for await (const chunk of request) {
    const value = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    length += value.length;
    if (length > 8_192) throw new RequestError(413, 'Request body is too large');
    chunks.push(value);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown;
  } catch {
    throw new RequestError(400, 'Invalid JSON');
  }
}

class RequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify(body));
}

async function bootstrap(): Promise<void> {
  await manager.initialize();
  server.listen(port, '0.0.0.0');
}

void bootstrap().catch((error: unknown) => {
  console.error('Hosting engine failed to start', error);
  process.exitCode = 1;
});
