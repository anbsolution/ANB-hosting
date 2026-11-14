import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { Request } from 'express';
import { AuthenticatedUser, SessionPayload } from './auth.types';
import { PrismaService } from '../prisma/prisma.service';

export const SESSION_COOKIE = 'anb_session';

@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly jwt: JwtService,
    private readonly config: ConfigService,
    private readonly prisma: PrismaService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<Request & { user?: AuthenticatedUser }>();
    const token = request.cookies?.[SESSION_COOKIE];
    if (!token) throw new UnauthorizedException('Authentication required');

    let payload: SessionPayload;
    try {
      payload = await this.jwt.verifyAsync<SessionPayload>(token, {
        secret: this.config.getOrThrow<string>('JWT_SECRET'),
      });
    } catch {
      throw new UnauthorizedException('Invalid or expired session');
    }
    if (!payload.sub || !payload.tenantId) throw new UnauthorizedException('Invalid session');

    const membership = await this.prisma.membership.findUnique({
      where: { userId_tenantId: { userId: payload.sub, tenantId: payload.tenantId } },
      select: { role: true },
    });
    if (!membership) throw new UnauthorizedException('Session is no longer valid');

    request.user = {
      userId: payload.sub,
      tenantId: payload.tenantId,
      role: membership.role,
    };
    return true;
  }
}
