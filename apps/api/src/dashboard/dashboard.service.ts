import { Injectable } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class DashboardService {
  constructor(private readonly prisma: PrismaService) {}

  async getSummary(tenantId: string) {
    const [websites, activeWebsites, domains, recentActivity] = await Promise.all([
      this.prisma.website.count({ where: { tenantId } }),
      this.prisma.website.count({ where: { tenantId, status: 'ACTIVE' } }),
      this.prisma.domain.count({ where: { tenantId } }),
      this.prisma.auditLog.findMany({
        where: { tenantId },
        orderBy: { createdAt: 'desc' },
        take: 5,
        select: {
          id: true,
          action: true,
          createdAt: true,
          actor: { select: { email: true } },
        },
      }),
    ]);
    return { websites, activeWebsites, domains, recentActivity };
  }
}
