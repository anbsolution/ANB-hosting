import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { HostingEngineClient } from './hosting-engine.client';
import { WebsitesController } from './websites.controller';
import { WebsitesService } from './websites.service';

@Module({
  imports: [AuthModule],
  controllers: [WebsitesController],
  providers: [HostingEngineClient, WebsitesService],
})
export class WebsitesModule {}
