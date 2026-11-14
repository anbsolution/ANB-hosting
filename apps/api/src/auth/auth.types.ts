import { MembershipRole } from '@prisma/client';

export interface AuthenticatedUser {
  userId: string;
  tenantId: string;
  role: MembershipRole;
}

export interface AuthProfile {
  user: { id: string; email: string };
  tenant: { id: string; name: string };
  role: MembershipRole;
}

export interface AuthSession {
  token: string;
  profile: AuthProfile;
}

export interface SessionPayload {
  sub: string;
  tenantId: string;
  role: MembershipRole;
}

declare module 'express-serve-static-core' {
  interface Request {
    user?: AuthenticatedUser;
  }
}
