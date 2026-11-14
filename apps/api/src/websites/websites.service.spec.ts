import { NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PrismaService } from '../prisma/prisma.service';
import { HostingEngineClient } from './hosting-engine.client';
import { WebsitesService } from './websites.service';

describe('WebsitesService', () => {
  it('does not send a delete request for a website outside the active tenant', async () => {
    const findFirst = jest.fn().mockResolvedValue(null);
    const prisma = {
      website: { findFirst },
    } as unknown as PrismaService;
    const engine = {
      remove: jest.fn(),
    } as unknown as HostingEngineClient;
    const config = {
      getOrThrow: jest.fn().mockReturnValue('sites.example.test'),
    } as unknown as ConfigService;
    const service = new WebsitesService(prisma, engine, config);

    await expect(service.remove('tenant-a', 'user-a', 'website-id')).rejects.toBeInstanceOf(
      NotFoundException,
    );
    expect(findFirst).toHaveBeenCalledWith(
      expect.objectContaining({ where: { id: 'website-id', tenantId: 'tenant-a' } }),
    );
    expect(engine.remove).not.toHaveBeenCalled();
  });
});
