/// <reference types="vite/client" />

interface BridgeRuntimeConfig {
  GATEWAY_API_URL: string;
  BRIDGE_ID_API_URL: string;
}

interface Window {
  __BRIDGE_RUNTIME_CONFIG__: BridgeRuntimeConfig;
}
