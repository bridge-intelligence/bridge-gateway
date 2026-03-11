import axios from 'axios';
import { runtimeConfig } from '../config/runtimeConfig';
import { useAuthStore } from '../stores/authStore';
import type { GatewayRoute, GatewayPlugin, GatewayHealth, GatewayConfig } from '../types';

const client = axios.create({
  baseURL: runtimeConfig.gatewayApiUrl,
  headers: { 'Content-Type': 'application/json' },
});

client.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      useAuthStore.getState().logout();
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

export const gatewayApi = {
  getRoutes: () =>
    client.get<GatewayRoute[]>('/gateway/admin/routes').then((r) => r.data),

  getHealth: () =>
    client.get<GatewayHealth>('/gateway/admin/health').then((r) => r.data),

  getConfig: () =>
    client.get<GatewayConfig>('/gateway/admin/config').then((r) => r.data),

  getPlugins: () =>
    client.get<GatewayPlugin[]>('/gateway/admin/plugins').then((r) => r.data),

  getPlugin: (id: string) =>
    client.get<GatewayPlugin>(`/gateway/admin/plugins/${id}`).then((r) => r.data),

  enablePlugin: (id: string, config?: Record<string, unknown>) =>
    client.post(`/gateway/admin/plugins/${id}/enable`, config ?? {}).then((r) => r.data),

  disablePlugin: (id: string) =>
    client.post(`/gateway/admin/plugins/${id}/disable`).then((r) => r.data),

  getPluginHealth: (id: string) =>
    client.get(`/gateway/admin/plugins/${id}/health`).then((r) => r.data),

  getActuatorHealth: () =>
    client.get('/actuator/health').then((r) => r.data),

  getActuatorInfo: () =>
    client.get('/actuator/info').then((r) => r.data),

  getGatewayRoutes: () =>
    client.get('/actuator/gateway/routes').then((r) => r.data),
};
