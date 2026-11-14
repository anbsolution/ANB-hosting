import { CanActivate, ExecutionContext, ForbiddenException, Injectable } from '@nestjs/common';
import { timingSafeEqual } from 'node:crypto';
import { Request } from 'express';

export const CSRF_COOKIE = 'anb_csrf';

@Injectable()
export class CsrfGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<Request>();
    const cookieToken = request.cookies?.[CSRF_COOKIE];
    const headerToken = request.get('x-csrf-token');

    if (
      typeof cookieToken !== 'string' ||
      typeof headerToken !== 'string' ||
      !/^[a-f0-9]{64}$/.test(cookieToken) ||
      !/^[a-f0-9]{64}$/.test(headerToken)
    ) {
      throw new ForbiddenException('CSRF validation failed');
    }

    const cookieBuffer = Buffer.from(cookieToken, 'hex');
    const headerBuffer = Buffer.from(headerToken, 'hex');
    if (!timingSafeEqual(cookieBuffer, headerBuffer)) {
      throw new ForbiddenException('CSRF validation failed');
    }
    return true;
  }
}
