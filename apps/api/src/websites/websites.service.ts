import {
  BadRequestException,
  ConflictException,
  Injectable,
  Logger,
  NotFoundException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Website, WebsiteStatus } from '@prisma/client';
import { randomBytes } from 'node:crypto';
import { PrismaService } from '../prisma/prisma.service';
import { CreateWebsiteDto } from './dto/create-website.dto';
import { HostingEngineClient } from './hosting-engine.client';

const websiteFields = {
  id: true,
  name: true,
  slug: true,
  hostname: true,
  status: true,
  errorCode: true,
  createdAt: true,
  updatedAt: true,
} as const;

type WebsiteView = Pick<
  Website,
  'id' | 'name' | 'slug' | 'hostname' | 'status' | 'errorCode' | 'createdAt' | 'updatedAt'
>;

@Injectable()
export class WebsitesService {
  private readonly logger = new Logger(WebsitesService.name);
  private readonly domainSuffix: string;

  constructor(
    private readonly prisma: PrismaService,
    private readonly engine: HostingEngineClient,
    config: ConfigService,
  ) {
    this.domainSuffix = config.getOrThrow<string>('WEBSITE_DOMAIN_SUFFIX').toLowerCase();
  }

  list(tenantId: string): Promise<WebsiteView[]> {
    return this.prisma.website.findMany({
      where: { tenantId },
      orderBy: { createdAt: 'desc' },
      select: websiteFields,
    });
  }

  async create(
    tenantId: string,
    actorId: string,
    input: CreateWebsiteDto,
  ): Promise<WebsiteView> {
    const name = input.name.trim();
    const baseSlug = name
      .normalize('NFKD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 40)
      .replace(/-+$/g, '');
    if (!baseSlug) throw new BadRequestException('Website name must contain letters or numbers');

    const slug = `${baseSlug}-${randomBytes(4).toString('hex')}`;
    const hostname = `${slug}.${this.domainSuffix}`;
    let website: Website;
    try {
      website = await this.prisma.website.create({
        data: { tenantId, name, slug, hostname, status: WebsiteStatus.PROVISIONING },
      });
    } catch (error) {
      if (isUniqueConstraintError(error)) {
        throw new ConflictException('Unable to allocate a unique website address');
      }
      throw error;
    }

    let provisioned: { containerId: string };
    try {
      provisioned = await this.engine.provision(website.id, website.hostname);
    } catch (error) {
      await this.markFailed(tenantId, actorId, website.id, 'PROVISION_FAILED');
      this.logger.error(`Website provisioning failed for ${website.id}`);
      if (error instanceof ServiceUnavailableException) throw error;
      throw new ServiceUnavailableException('Website provisioning failed');
    }

    const active = await this.prisma.website.update({
      where: { id: website.id, tenantId },
      data: {
        status: WebsiteStatus.ACTIVE,
        containerId: provisioned.containerId,
        errorCode: null,
      },
      select: websiteFields,
    });
    await this.audit(tenantId, actorId, 'website.created', website.id);
    return active;
  }

  async start(tenantId: string, actorId: string, id: string): Promise<WebsiteView> {
    return this.transition(
      tenantId,
      actorId,
      id,
      'website.started',
      [WebsiteStatus.STOPPED, WebsiteStatus.FAILED],
      WebsiteStatus.STARTING,
      WebsiteStatus.ACTIVE,
      async () => this.engine.start(id, await this.getHostname(tenantId, id)),
    );
  }

  async stop(tenantId: string, actorId: string, id: string): Promise<WebsiteView> {
    return this.transition(
      tenantId,
      actorId,
      id,
      'website.stopped',
      [WebsiteStatus.ACTIVE],
      WebsiteStatus.STOPPING,
      WebsiteStatus.STOPPED,
      () => this.engine.stop(id),
    );
  }

  async restart(tenantId: string, actorId: string, id: string): Promise<WebsiteView> {
    return this.transition(
      tenantId,
      actorId,
      id,
      'website.restarted',
      [WebsiteStatus.ACTIVE],
      WebsiteStatus.RESTARTING,
      WebsiteStatus.ACTIVE,
      () => this.engine.restart(id),
    );
  }

  async remove(
    tenantId: string,
    actorId: string,
    id: string,
  ): Promise<{ deleted: true }> {
    const website = await this.findForTenant(tenantId, id);
    if (
      [
        WebsiteStatus.PROVISIONING,
        WebsiteStatus.STARTING,
        WebsiteStatus.STOPPING,
        WebsiteStatus.RESTARTING,
      ].some((status) => status === website.status)
    ) {
      throw new ConflictException('Website is changing; retry the request');
    }
    const claimed = await this.prisma.website.updateMany({
      where: { id, tenantId, status: website.status },
      data: { status: WebsiteStatus.DELETING },
    });
    if (claimed.count !== 1) throw new ConflictException('Website is changing; retry the request');

    try {
      await this.engine.remove(id);
    } catch {
      await this.markFailed(tenantId, actorId, id, 'DELETE_FAILED');
      this.logger.error(`Website deletion failed for ${id}`);
      throw new ServiceUnavailableException('Website could not be deleted');
    }

    await this.prisma.$transaction([
      this.prisma.auditLog.create({
        data: {
          tenantId,
          actorId,
          action: 'website.deleted',
          resource: 'website',
          resourceId: id,
        },
      }),
      this.prisma.website.deleteMany({ where: { id, tenantId } }),
    ]);
    return { deleted: true };
  }

  async status(
    tenantId: string,
    id: string,
  ): Promise<{ id: string; hostname: string; status: WebsiteStatus; running: boolean }> {
    const website = await this.findForTenant(tenantId, id);
    const runtime = await this.engine.status(id);
    return {
      id: website.id,
      hostname: website.hostname,
      status: website.status,
      running: runtime.running,
    };
  }

  private async transition(
    tenantId: string,
    actorId: string,
    id: string,
    action: string,
    allowed: WebsiteStatus[],
    transient: WebsiteStatus,
    target: WebsiteStatus,
    operation: () => Promise<unknown>,
  ): Promise<WebsiteView> {
    const website = await this.findForTenant(tenantId, id);
    if (website.status === target) return website;
    if (!allowed.includes(website.status)) {
      throw new ConflictException(`Cannot perform this operation while website is ${website.status}`);
    }

    const claimed = await this.prisma.website.updateMany({
      where: { id, tenantId, status: website.status },
      data: { status: transient },
    });
    if (claimed.count !== 1) throw new ConflictException('Website is changing; retry the request');

    try {
      await operation();
    } catch {
      await this.markFailed(tenantId, actorId, id, 'LIFECYCLE_OPERATION_FAILED');
      this.logger.error(`Website lifecycle operation failed for ${id}`);
      throw new ServiceUnavailableException('Website lifecycle operation failed');
    }

    const updated = await this.prisma.website.update({
      where: { id, tenantId },
      data: { status: target, errorCode: null },
      select: websiteFields,
    });
    await this.audit(tenantId, actorId, action, id);
    return updated;
  }

  private async findForTenant(tenantId: string, id: string): Promise<WebsiteView> {
    const website = await this.prisma.website.findFirst({
      where: { id, tenantId },
      select: websiteFields,
    });
    if (!website) throw new NotFoundException('Website not found');
    return website;
  }

  private async getHostname(tenantId: string, id: string): Promise<string> {
    const website = await this.prisma.website.findFirst({
      where: { id, tenantId },
      select: { hostname: true },
    });
    if (!website) throw new NotFoundException('Website not found');
    return website.hostname;
  }

  private async markFailed(
    tenantId: string,
    actorId: string,
    id: string,
    errorCode: string,
  ): Promise<void> {
    await this.prisma.website.updateMany({
      where: { id, tenantId },
      data: { status: WebsiteStatus.FAILED, errorCode },
    });
    await this.audit(tenantId, actorId, 'website.operation_failed', id, { errorCode });
  }

  private audit(
    tenantId: string,
    actorId: string,
    action: string,
    websiteId: string,
    metadata?: { errorCode: string },
  ): Promise<unknown> {
    return this.prisma.auditLog.create({
      data: {
        tenantId,
        actorId,
        action,
        resource: 'website',
        resourceId: websiteId,
        ...(metadata ? { metadata } : {}),
      },
    });
  }
}

function isUniqueConstraintError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    error.code === 'P2002'
  );
}
