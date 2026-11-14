import { Module } from '@nestjs/common';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { CsrfGuard } from './csrf.guard';
import { JwtAuthGuard } from './jwt-auth.guard';
import { RolesGuard } from './roles.guard';

@Module({
  controllers: [AuthController],
  providers: [AuthService, CsrfGuard, JwtAuthGuard, RolesGuard],
  exports: [CsrfGuard, JwtAuthGuard, RolesGuard],
})
export class AuthModule {}
