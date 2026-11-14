import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  ParseUUIDPipe,
  Post,
  UseGuards,
} from '@nestjs/common';
import { MembershipRole } from '@prisma/client';
import { AuthenticatedUser } from '../auth/auth.types';
import { CurrentUser } from '../auth/current-user.decorator';
import { CsrfGuard } from '../auth/csrf.guard';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { Roles } from '../auth/roles.decorator';
import { RolesGuard } from '../auth/roles.guard';
import { CreateWebsiteDto } from './dto/create-website.dto';
import { WebsitesService } from './websites.service';

@Controller('websites')
@UseGuards(JwtAuthGuard, RolesGuard)
export class WebsitesController {
  constructor(private readonly websites: WebsitesService) {}

  @Get()
  list(@CurrentUser() user: AuthenticatedUser) {
    return this.websites.list(user.tenantId);
  }

  @Get(':id/status')
  status(@CurrentUser() user: AuthenticatedUser, @Param('id', ParseUUIDPipe) id: string) {
    return this.websites.status(user.tenantId, id);
  }

  @Post()
  @UseGuards(CsrfGuard)
  @Roles(MembershipRole.ADMIN)
  create(@CurrentUser() user: AuthenticatedUser, @Body() input: CreateWebsiteDto) {
    return this.websites.create(user.tenantId, user.userId, input);
  }

  @Post(':id/start')
  @HttpCode(HttpStatus.OK)
  @UseGuards(CsrfGuard)
  @Roles(MembershipRole.ADMIN)
  start(@CurrentUser() user: AuthenticatedUser, @Param('id', ParseUUIDPipe) id: string) {
    return this.websites.start(user.tenantId, user.userId, id);
  }

  @Post(':id/stop')
  @HttpCode(HttpStatus.OK)
  @UseGuards(CsrfGuard)
  @Roles(MembershipRole.ADMIN)
  stop(@CurrentUser() user: AuthenticatedUser, @Param('id', ParseUUIDPipe) id: string) {
    return this.websites.stop(user.tenantId, user.userId, id);
  }

  @Post(':id/restart')
  @HttpCode(HttpStatus.OK)
  @UseGuards(CsrfGuard)
  @Roles(MembershipRole.ADMIN)
  restart(@CurrentUser() user: AuthenticatedUser, @Param('id', ParseUUIDPipe) id: string) {
    return this.websites.restart(user.tenantId, user.userId, id);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.OK)
  @UseGuards(CsrfGuard)
  @Roles(MembershipRole.ADMIN)
  remove(@CurrentUser() user: AuthenticatedUser, @Param('id', ParseUUIDPipe) id: string) {
    return this.websites.remove(user.tenantId, user.userId, id);
  }
}
