import { PrismaService } from '../prisma/prisma.service';
import { DashboardService } from './dashboard.service';

describe('DashboardService', () => {
  it('scopes website, domain, and audit counts to the active tenant', async () => {
    const websiteCount = jest.fn().mockResolvedValueOnce(2).mockResolvedValueOnce(1);
    const domainCount = jest.fn().mockResolvedValue(3);
    const auditFindMany = jest.fn().mockResolvedValue([]);
    const prisma = {
      website: { count: websiteCount },
      domain: { count: domainCount },
      auditLog: { findMany: auditFindMany },
    } as unknown as PrismaService;
    const service = new DashboardService(prisma);

    await expect(service.getSummary('tenant-123')).resolves.toEqual({
      websites: 2,
      activeWebsites: 1,
      domains: 3,
      recentActivity: [],
    });

    expect(websiteCount).toHaveBeenNthCalledWith(1, { where: { tenantId: 'tenant-123' } });
    expect(websiteCount).toHaveBeenNthCalledWith(2, {
      where: { tenantId: 'tenant-123', status: 'ACTIVE' },
    });
    expect(domainCount).toHaveBeenCalledWith({ where: { tenantId: 'tenant-123' } });
    expect(auditFindMany).toHaveBeenCalledWith(
      expect.objectContaining({ where: { tenantId: 'tenant-123' } }),
    );
  });
});
