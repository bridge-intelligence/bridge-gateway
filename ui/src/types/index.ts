export interface GatewayRoute {
  id: string;
  path: string;
  uri: string;
  enabled: boolean;
  circuitBreaker?: {
    state: string;
    failureRate: number;
    callCount: number;
  };
  plugins: string[];
}

export interface GatewayPlugin {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  order: number;
  phase: string;
  config: Record<string, unknown>;
  healthy: boolean;
}

export interface GatewayHealth {
  status: string;
  uptime: string;
  routes: {
    total: number;
    active: number;
  };
  plugins: {
    total: number;
    enabled: number;
  };
  downstream: DownstreamService[];
}

export interface DownstreamService {
  name: string;
  url: string;
  status: string;
  latencyMs: number;
}

export interface GatewayConfig {
  routes: Record<string, GatewayRoute>;
  plugins: Record<string, GatewayPlugin>;
  auth: {
    headerName: string;
    keyCount: number;
  };
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  access_token: string;
  brdg_id: string;
  token_type: string;
}

export interface UserInfo {
  brdg_id: string;
  email: string;
  roles: string[];
}
