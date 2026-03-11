import axios from 'axios';
import { runtimeConfig } from '../config/runtimeConfig';
import type { LoginRequest, LoginResponse, UserInfo } from '../types';

const authClient = axios.create({
  baseURL: runtimeConfig.bridgeIdApiUrl,
  headers: { 'Content-Type': 'application/json' },
});

export const authApi = {
  login: (data: LoginRequest) =>
    authClient.post<LoginResponse>('/api/v1/login', data).then((r) => r.data),

  userinfo: (token: string) =>
    authClient
      .get<UserInfo>('/api/v1/userinfo', {
        headers: { Authorization: `Bearer ${token}` },
      })
      .then((r) => r.data),

  getSocialAuthUrl: (provider: string, redirectUri?: string): string => {
    const base = `${runtimeConfig.bridgeIdApiUrl}/api/v1/auth/social/${provider}`;
    if (redirectUri) {
      return `${base}?redirect_uri=${encodeURIComponent(redirectUri)}`;
    }
    return base;
  },
};
