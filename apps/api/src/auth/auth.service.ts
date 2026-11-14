import {
  ConflictException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { MembershipRole } from '@prisma/client';
import * as argon2 from 'argon2';
import { randomUUID } from 'node:crypto';
import { PrismaService } from '../prisma/prisma.service';
import { AuthenticatedUser, AuthProfile, AuthSession, SessionPayload } from './auth.types';
import { LoginDto } from './dto/login.dto';
import { RegisterDto } from './dto/register.dto';

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly jwt: JwtService,
    private readonly config: ConfigService,
  ) {}

  async register(input: RegisterDto): Promise<AuthSession> {
    const email = input.email.trim().toLowerCase();
    const passwordHash = await argon2.hash(input.password, { type: argon2.argon2id });

    try {
      const user = await this.prisma.$transaction(async (transaction) => {
        const createdUser = await transaction.user.create({
          data: { email, passwordHash },
        });
        const tenant = await transaction.tenant.create({
          data: {
            name: input.workspaceName.trim(),
            slug: `workspace-${randomUUID()}`,
          },
        });
        await transaction.membership.create({
          data: { userId: createdUser.id, tenantId: tenant.id, role: MembershipRole.ADMIN },
        });
        await transaction.auditLog.create({
          data: {
            actorId: createdUser.id,
            tenantId: tenant.id,
            action: 'account.registered',
          },
        });
        return {
          id: createdUser.id,
          email: createdUser.email,
          tenantId: tenant.id,
          tenantName: tenant.name,
          role: MembershipRole.ADMIN,
        };
      });

      return {
        token: await this.signSession(user.id, user.tenantId, user.role),
        profile: {
          user: { id: user.id, email: user.email },
          tenant: { id: user.tenantId, name: user.tenantName },
          role: user.role,
        },
      };
    } catch (error) {
      if (
        typeof error === 'object' &&
        error !== null &&
        'code' in error &&
        error.code === 'P2002'
      ) {
        throw new ConflictException('An account with this email already exists');
      }
      throw error;
    }
  }

  async login(input: LoginDto): Promise<AuthSession> {
    const email = input.email.trim().toLowerCase();
    const user = await this.prisma.user.findUnique({
      where: { email },
      include: { memberships: { orderBy: { createdAt: 'asc' }, take: 1 } },
    });
    const validPassword =
      user !== null && (await argon2.verify(user.passwordHash, input.password));
    if (!user || !validPassword || !user.memberships[0]) {
      throw new UnauthorizedException('Email or password is incorrect');
    }

    const membership = user.memberships[0];
    await this.prisma.auditLog.create({
      data: {
        actorId: user.id,
        tenantId: membership.tenantId,
        action: 'auth.login',
      },
    });
    const tenant = await this.prisma.tenant.findUniqueOrThrow({
      where: { id: membership.tenantId },
      select: { id: true, name: true },
    });
    return {
      token: await this.signSession(user.id, membership.tenantId, membership.role),
      profile: {
        user: { id: user.id, email: user.email },
        tenant,
        role: membership.role,
      },
    };
  }

  async getProfile(userId: string, tenantId: string): Promise<AuthProfile> {
    const membership = await this.prisma.membership.findUnique({
      where: { userId_tenantId: { userId, tenantId } },
      include: { user: { select: { id: true, email: true } }, tenant: { select: { id: true, name: true } } },
    });
    if (!membership) throw new UnauthorizedException('Session is no longer valid');
    return { user: membership.user, tenant: membership.tenant, role: membership.role };
  }

  async recordLogout(user: AuthenticatedUser): Promise<void> {
    await this.prisma.auditLog.create({
      data: {
        actorId: user.userId,
        tenantId: user.tenantId,
        action: 'auth.logout',
      },
    });
  }

  private signSession(userId: string, tenantId: string, role: MembershipRole): Promise<string> {
    const payload: SessionPayload = { sub: userId, tenantId, role };
    return this.jwt.signAsync(payload, {
      secret: this.config.getOrThrow<string>('JWT_SECRET'),
      expiresIn: '15m',
    });
  }
}
