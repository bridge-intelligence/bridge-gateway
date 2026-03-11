const cfg = window.__BRIDGE_RUNTIME_CONFIG__ ?? {};

export const runtimeConfig = {
  gatewayApiUrl: cfg.GATEWAY_API_URL || '',
  bridgeIdApiUrl: cfg.BRIDGE_ID_API_URL || '',
};
