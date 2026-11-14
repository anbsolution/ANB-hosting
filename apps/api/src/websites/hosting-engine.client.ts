import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

@Injectable()
export class HostingEngineClient {
  private readonly baseUrl: string;
  private readonly token: string;

  constructor(config: ConfigService) {
    this.baseUrl = config.getOrThrow<string>('ENGINE_URL').replace(/\/+$/, '');
    this.token = config.getOrThrow<string>('ENGINE_TOKEN');
  }

  provision(id: string, hostname: string): Promise<{ containerId: string }> {
    return this.request('/v1/sites', 'POST', { id, hostname });
  }

  start(id: string, hostname: string): Promise<void> {
    return this.request(`/v1/sites/${id}/start`, 'POST', { hostname });
  }

  stop(id: string): Promise<void> {
    return this.request(`/v1/sites/${id}/stop`, 'POST');
  }

  restart(id: string): Promise<void> {
    return this.request(`/v1/sites/${id}/restart`, 'POST');
  }

  remove(id: string): Promise<void> {
    return this.request(`/v1/sites/${id}`, 'DELETE');
  }

  async status(id: string): Promise<{ running: boolean }> {
    return this.request(`/v1/sites/${id}`, 'GET');
  }

  private async request<T>(path: string, method: string, body?: unknown): Promise<T> {
    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${this.token}`,
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(body ? { body: JSON.stringify(body) } : {}),
        signal: AbortSignal.timeout(5_000),
      });
    } catch {
      throw new ServiceUnavailableException('Hosting engine is unavailable');
    }

    if (!response.ok) {
      throw new ServiceUnavailableException('Hosting engine rejected the operation');
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }
}
