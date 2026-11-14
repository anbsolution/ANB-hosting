import { Body, Controller, Get, HttpCode, HttpStatus, Post, Res, UseGuards } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Throttle } from '@nestjs/throttler';
import { Response } from 'express';
import { randomBytes } from 'node:crypto';
import { AuthenticatedUser, AuthProfile } from './auth.types';
import { AuthService } from './auth.service';
import { CurrentUser } from './current-user.decorator';
import { CSRF_COOKIE, CsrfGuard } from './csrf.guard';
import { LoginDto } from './dto/login.dto';
import { RegisterDto } from './dto/register.dto';
import { JwtAuthGuard, SESSION_COOKIE } from './jwt-auth.guard';

@Controller('auth')
export class AuthController {
  constructor(
    private readonly auth: AuthService,
    private readonly config: ConfigService,
  ) {}

  @Get('csrf')
  getCsrfToken(@Res({ passthrough: true }) response: Response): { csrfToken: string } {
    return { csrfToken: this.setCsrfCookie(response) };
  }

  @Post('register')
  @UseGuards(CsrfGuard)
  @Throttle({ default: { limit: 5, ttl: 60_000 } })
  async register(
    @Body() input: RegisterDto,
    @Res({ passthrough: true }) response: Response,
  ): Promise<AuthProfile> {
    const { token, profile } = await this.auth.register(input);
    this.setSessionCookie(response, token);
    this.setCsrfCookie(response);
    return profile;
  }

  @Post('login')
  @HttpCode(HttpStatus.OK)
  @UseGuards(CsrfGuard)
  @Throttle({ default: { limit: 5, ttl: 60_000 } })
  async login(
    @Body() input: LoginDto,
    @Res({ passthrough: true }) response: Response,
  ): Promise<AuthProfile> {
    const { token, profile } = await this.auth.login(input);
    this.setSessionCookie(response, token);
    this.setCsrfCookie(response);
    return profile;
  }

  @Get('me')
  @UseGuards(JwtAuthGuard)
  async me(@CurrentUser() user: AuthenticatedUser) {
    return this.auth.getProfile(user.userId, user.tenantId);
  }

  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  @UseGuards(JwtAuthGuard, CsrfGuard)
  async logout(
    @CurrentUser() user: AuthenticatedUser,
    @Res({ passthrough: true }) response: Response,
  ): Promise<void> {
    await this.auth.recordLogout(user);
    response.clearCookie(SESSION_COOKIE, this.cookieOptions());
    response.clearCookie(CSRF_COOKIE, this.csrfCookieOptions());
  }

  private setSessionCookie(response: Response, token: string): void {
    response.cookie(SESSION_COOKIE, token, {
      ...this.cookieOptions(),
      maxAge: 15 * 60 * 1000,
    });
  }

  private setCsrfCookie(response: Response): string {
    const token = randomBytes(32).toString('hex');
    response.cookie(CSRF_COOKIE, token, this.csrfCookieOptions());
    return token;
  }

  private csrfCookieOptions() {
    return {
      httpOnly: false,
      secure: this.config.get<boolean>('COOKIE_SECURE', false),
      sameSite: 'strict' as const,
      path: '/',
    };
  }

  private cookieOptions() {
    return {
      httpOnly: true,
      secure: this.config.get<boolean>('COOKIE_SECURE', false),
      sameSite: 'strict' as const,
      path: '/api/v1',
    };
  }
}
