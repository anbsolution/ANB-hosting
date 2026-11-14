import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import Docker from 'dockerode';

const SITE_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const HOST_PATTERN = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const ROUTE_PATTERN = /^"([^"]+)" "(anb-site-[0-9a-f-]+):8080";$/;

export interface EngineConfig {
  socketPath: string;
  siteImage: string;
  nginxContainer: string;
  configDirectory: string;
  domainSuffix: string;
}

export class SiteManager {
  private readonly docker: Docker;
  private routes = new Map<string, string>();
  private routeQueue: Promise<void> = Promise.resolve();

  constructor(private readonly config: EngineConfig) {
    this.docker = new Docker({ socketPath: config.socketPath });
  }

  async initialize(): Promise<void> {
    await mkdir(this.config.configDirectory, { recursive: true });
    const routePath = this.routePath();
    let existing = '';
    try {
      existing = await readFile(routePath, 'utf8');
    } catch (error) {
      if (!isNotFound(error)) throw error;
    }

    for (const line of existing.split('\n').filter(Boolean)) {
      const match = ROUTE_PATTERN.exec(line);
      if (!match || !isValidHostname(match[1], this.config.domainSuffix)) {
        throw new Error('Invalid persisted site route configuration');
      }
      const siteId = match[2].slice('anb-site-'.length);
      if (!SITE_ID_PATTERN.test(siteId)) throw new Error('Invalid persisted site identifier');
      this.routes.set(match[1], siteId);
    }
    for (const [hostname, id] of this.routes) {
      try {
        const container = await this.findContainer(id);
        const inspect = await container.inspect();
        if (inspect.Config.Labels?.['anb.hostname'] !== hostname) {
          this.routes.delete(hostname);
          continue;
        }
        if (!inspect.State.Running) await container.start();
        await this.ensureSiteNetwork(id);
        const ipAddress = await this.connectNginxToSite(id);
        this.siteClientIps.set(ipAddress, true);
      } catch (error) {
        if (
          !isNotFound(error) &&
          !(error instanceof Error && error.message === 'Website container not found')
        ) {
          throw error;
        }
        this.routes.delete(hostname);
      }
    }
    await this.persistRoutes();
    if (this.routes.size) await this.reloadNginx();
  }

  private readonly siteClientIps = new Map<string, true>();

  async provision(id: string, hostname: string): Promise<{ containerId: string }> {
    this.validateSite(id, hostname);
    const container = await this.ensureContainer(id, hostname);
    try {
      const inspect = await container.inspect();
      if (!inspect.State.Running) await container.start();
      const ipAddress = await this.connectNginxToSite(id);
      await this.addRoute(hostname, id, ipAddress);
    } catch (error) {
      await this.removeContainer(id);
      await this.removeSiteNetwork(id);
      throw error;
    }
    return { containerId: container.id };
  }

  async start(id: string, hostname: string): Promise<void> {
    this.validateSite(id, hostname);
    const container = await this.ensureContainer(id, hostname);
    const inspect = await container.inspect();
    if (!inspect.State.Running) await container.start();
    const labels = inspect.Config.Labels ?? {};
    if (labels['anb.hostname'] !== hostname) {
      throw new Error('Website container has invalid routing metadata');
    }
    const ipAddress = await this.connectNginxToSite(id);
    await this.addRoute(hostname, id, ipAddress);
  }

  async stop(id: string): Promise<void> {
    this.validateId(id);
    const container = await this.findContainer(id);
    await this.removeRouteForId(id);
    const inspect = await container.inspect();
    if (inspect.State.Running) await container.stop({ t: 10 });
  }

  async restart(id: string): Promise<void> {
    this.validateId(id);
    const container = await this.findContainer(id);
    const inspect = await container.inspect();
    if (!inspect.State.Running) {
      await container.start();
    } else {
      await container.restart({ t: 10 });
    }
    const hostname = inspect.Config.Labels?.['anb.hostname'];
    if (typeof hostname !== 'string' || !isValidHostname(hostname, this.config.domainSuffix)) {
      throw new Error('Website container has invalid routing metadata');
    }
    const ipAddress = await this.connectNginxToSite(id);
    await this.addRoute(hostname, id, ipAddress);
  }

  async remove(id: string): Promise<void> {
    this.validateId(id);
    await this.removeRouteForId(id);
    await this.removeContainer(id);
    await this.removeSiteNetwork(id);
  }

  async status(id: string): Promise<{ running: boolean }> {
    this.validateId(id);
    try {
      const inspect = await this.docker.getContainer(this.containerName(id)).inspect();
      return { running: inspect.State.Running };
    } catch (error) {
      if (isNotFound(error)) return { running: false };
      throw error;
    }
  }

  private async ensureContainer(id: string, hostname: string): Promise<Docker.Container> {
    await this.ensureSiteNetwork(id);
    const name = this.containerName(id);
    try {
      const existing = this.docker.getContainer(name);
      const inspect = await existing.inspect();
      if (
        inspect.Config.Labels?.['anb.managed'] !== 'hosting-engine' ||
        inspect.Config.Labels?.['anb.website_id'] !== id ||
        inspect.Config.Labels?.['anb.hostname'] !== hostname
      ) {
        throw new Error('Container name is already in use');
      }
      return existing;
    } catch (error) {
      if (!isNotFound(error)) throw error;
    }

    return this.docker.createContainer({
      Image: this.config.siteImage,
      name,
      User: '101:101',
      Labels: {
        'anb.managed': 'hosting-engine',
        'anb.website_id': id,
        'anb.hostname': hostname,
      },
      ExposedPorts: { '8080/tcp': {} },
      HostConfig: {
        NetworkMode: this.networkName(id),
        Memory: 268_435_456,
        MemorySwap: 268_435_456,
        NanoCpus: 500_000_000,
        PidsLimit: 64,
        CapDrop: ['ALL'],
        SecurityOpt: ['no-new-privileges:true'],
        ReadonlyRootfs: true,
        Tmpfs: {
          '/tmp': 'rw,noexec,nosuid,size=16m',
          '/var/cache/nginx': 'rw,noexec,nosuid,size=16m',
          '/var/run': 'rw,noexec,nosuid,size=1m',
        },
        RestartPolicy: { Name: 'unless-stopped' },
        LogConfig: {
          Type: 'json-file',
          Config: { 'max-size': '10m', 'max-file': '3' },
        },
      },
    });
  }

  private async findContainer(id: string): Promise<Docker.Container> {
    try {
      const container = this.docker.getContainer(this.containerName(id));
      const inspect = await container.inspect();
      if (
        inspect.Config.Labels?.['anb.managed'] !== 'hosting-engine' ||
        inspect.Config.Labels?.['anb.website_id'] !== id
      ) {
        throw new Error('Container is not managed by the hosting engine');
      }
      return container;
    } catch (error) {
      if (isNotFound(error)) throw new Error('Website container not found');
      throw error;
    }
  }

  private async removeContainer(id: string): Promise<void> {
    try {
      const container = await this.findContainer(id);
      const inspect = await container.inspect();
      if (inspect.State.Running) await container.stop({ t: 10 });
      await container.remove({ force: false });
    } catch (error) {
      if (
        !isNotFound(error) &&
        !(error instanceof Error && error.message === 'Website container not found')
      ) {
        throw error;
      }
    }
  }

  private addRoute(hostname: string, id: string, ipAddress: string): Promise<void> {
    return this.withRouteLock(async () => {
      const previous = new Map(this.routes);
      const previousIps = new Map(this.siteClientIps);
      this.routes.set(hostname, id);
      this.siteClientIps.set(ipAddress, true);
      try {
        await this.persistRoutes();
        await this.reloadNginx();
      } catch (error) {
        this.routes = previous;
        this.siteClientIps.clear();
        for (const [ip] of previousIps) this.siteClientIps.set(ip, true);
        await this.persistRoutes();
        throw error;
      }
    });
  }

  private removeRouteForId(id: string): Promise<void> {
    return this.withRouteLock(async () => {
      const previous = new Map(this.routes);
      const previousIps = new Map(this.siteClientIps);
      for (const [hostname, routeId] of this.routes) {
        if (routeId === id) this.routes.delete(hostname);
      }
      const containerIp = await this.containerIp(id);
      if (containerIp) this.siteClientIps.delete(containerIp);
      try {
        await this.persistRoutes();
        await this.reloadNginx();
      } catch (error) {
        this.routes = previous;
        this.siteClientIps.clear();
        for (const [ip] of previousIps) this.siteClientIps.set(ip, true);
        await this.persistRoutes();
        throw error;
      }
    });
  }

  private withRouteLock(operation: () => Promise<void>): Promise<void> {
    const result = this.routeQueue.then(operation);
    this.routeQueue = result.catch(() => undefined);
    return result;
  }

  private async persistRoutes(): Promise<void> {
    const routes = [...this.routes.entries()];
    const contents = routes
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([hostname, id]) => `"${hostname}" "${this.containerName(id)}:8080";`)
      .join('\n');
    const temporaryPath = `${this.routePath()}.tmp`;
    await writeFile(temporaryPath, contents ? `${contents}\n` : '', { mode: 0o644 });
    await rename(temporaryPath, this.routePath());

    const previews = routes
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([hostname, id]) => {
        const slug = hostname.slice(0, -this.config.domainSuffix.length - 1);
        return `~^/preview/${slug}(/|$) "${this.containerName(id)}:8080";`;
      })
      .join('\n');
    const previewPath = `${this.config.configDirectory}/previews.map`;
    const previewTemporaryPath = `${previewPath}.tmp`;
    await writeFile(previewTemporaryPath, previews ? `${previews}\n` : '', {
      mode: 0o644,
    });
    await rename(previewTemporaryPath, previewPath);

    const blockedClients = [...this.siteClientIps.keys()]
      .sort((left, right) => left.localeCompare(right))
      .map((ipAddress) => `"${ipAddress}" 1;`)
      .join('\n');
    const clientPath = `${this.config.configDirectory}/site_clients.map`;
    const clientTemporaryPath = `${clientPath}.tmp`;
    await writeFile(clientTemporaryPath, blockedClients ? `${blockedClients}\n` : '', {
      mode: 0o644,
    });
    await rename(clientTemporaryPath, clientPath);
  }

  private async reloadNginx(): Promise<void> {
    const nginx = this.docker.getContainer(this.config.nginxContainer);
    await nginx.kill({ signal: 'SIGHUP' });
  }

  private routePath(): string {
    return `${this.config.configDirectory}/upstreams.map`;
  }

  private networkName(id: string): string {
    return `anb-site-net-${id}`;
  }

  private async ensureSiteNetwork(id: string): Promise<void> {
    const name = this.networkName(id);
    let network: Docker.Network;
    try {
      network = this.docker.getNetwork(name);
      const inspect = await network.inspect();
      if (
        inspect.Labels?.['anb.managed'] !== 'hosting-engine' ||
        inspect.Labels?.['anb.website_id'] !== id
      ) {
        throw new Error('Network name is already in use');
      }
    } catch (error) {
      if (!isNotFound(error)) throw error;
      network = await this.docker.createNetwork({
        Name: name,
        Driver: 'bridge',
        Internal: true,
        Attachable: false,
        Labels: { 'anb.managed': 'hosting-engine', 'anb.website_id': id },
      });
    }

    try {
      const container = await this.docker.getContainer(this.containerName(id)).inspect();
      const networkInspect = await network.inspect();
      if (!networkInspect.Containers?.[container.Id]) {
        await network.connect({ Container: container.Id });
      }
    } catch (error) {
      if (!isNotFound(error)) throw error;
    }
  }

  private async connectNginxToSite(id: string): Promise<string> {
    const network = this.docker.getNetwork(this.networkName(id));
    const [networkInspect, nginxInspect, siteIp] = await Promise.all([
      network.inspect(),
      this.docker.getContainer(this.config.nginxContainer).inspect(),
      this.containerIp(id),
    ]);
    if (!networkInspect.Containers?.[nginxInspect.Id]) {
      await network.connect({ Container: nginxInspect.Id });
    }
    if (!siteIp) throw new Error('Website container has no isolated network address');
    return siteIp;
  }

  private async containerIp(id: string): Promise<string | null> {
    try {
      const inspect = await this.docker.getContainer(this.containerName(id)).inspect();
      const network = inspect.NetworkSettings.Networks?.[this.networkName(id)];
      return network?.IPAddress || null;
    } catch (error) {
      if (isNotFound(error)) return null;
      throw error;
    }
  }

  private async removeSiteNetwork(id: string): Promise<void> {
    const networkName = this.networkName(id);
    try {
      const network = this.docker.getNetwork(networkName);
      const inspect = await network.inspect();
      if (
        inspect.Labels?.['anb.managed'] !== 'hosting-engine' ||
        inspect.Labels?.['anb.website_id'] !== id
      ) {
        throw new Error('Network is not managed by the hosting engine');
      }
      const nginx = this.docker.getContainer(this.config.nginxContainer);
      const nginxInspect = await nginx.inspect();
      if (inspect.Containers?.[nginxInspect.Id]) {
        await network.disconnect({ Container: nginxInspect.Id, Force: true });
      }
      await network.remove();
    } catch (error) {
      if (!isNotFound(error)) throw error;
    }
  }

  private containerName(id: string): string {
    return `anb-site-${id}`;
  }

  private validateSite(id: string, hostname: string): void {
    this.validateId(id);
    if (!isValidHostname(hostname, this.config.domainSuffix)) {
      throw new Error('Invalid website hostname');
    }
  }

  private validateId(id: string): void {
    if (!SITE_ID_PATTERN.test(id)) throw new Error('Invalid website identifier');
  }
}

export function isValidHostname(hostname: string, domainSuffix: string): boolean {
  return (
    HOST_PATTERN.test(hostname) &&
    hostname.length <= 253 &&
    hostname.endsWith(`.${domainSuffix}`) &&
    hostname.slice(0, -domainSuffix.length - 1).split('.').length === 1
  );
}

function isNotFound(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'statusCode' in error &&
    error.statusCode === 404
  );
}
