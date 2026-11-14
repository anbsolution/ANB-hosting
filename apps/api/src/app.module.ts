import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { APP_GUARD } from '@nestjs/core';
import { JwtModule } from '@nestjs/jwt';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import * as Joi from 'joi';
import { AuthModule } from './auth/auth.module';
import { DashboardModule } from './dashboard/dashboard.module';
import { HealthController } from './health.controller';
import { PrismaModule } from './prisma/prisma.module';
import { WebsitesModule } from './websites/websites.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      envFilePath: ['../../.env', '.env'],
      validationSchema: Joi.object({
        NODE_ENV: Joi.string().valid('development', 'test', 'production').default('development'),
        PORT: Joi.number().port().default(3001),
        DATABASE_URL: Joi.string().required(),
        JWT_SECRET: Joi.string().min(32).required(),
        COOKIE_SECURE: Joi.boolean().when('NODE_ENV', {
          is: 'production',
          then: Joi.valid(true).required(),
          otherwise: Joi.boolean().default(false),
        }),
        WEB_ORIGINS: Joi.string().required(),
        ENGINE_URL: Joi.string().uri({ scheme: ['http', 'https'] }).required(),
        ENGINE_TOKEN: Joi.string().min(32).required(),
        WEBSITE_DOMAIN_SUFFIX: Joi.string()
          .pattern(/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/)
          .required(),
      }),
    }),
    JwtModule.register({ global: true }),
    ThrottlerModule.forRoot([{ ttl: 60_000, limit: 60 }]),
    PrismaModule,
    AuthModule,
    DashboardModule,
    WebsitesModule,
  ],
  controllers: [HealthController],
  providers: [{ provide: APP_GUARD, useClass: ThrottlerGuard }],
})
export class AppModule {}
