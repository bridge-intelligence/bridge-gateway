#!/bin/sh
cat > /usr/share/nginx/html/runtime-config.js <<EOF
window.__BRIDGE_RUNTIME_CONFIG__ = {
  GATEWAY_API_URL: "${GATEWAY_API_URL:-}",
  BRIDGE_ID_API_URL: "${BRIDGE_ID_API_URL:-}",
};
EOF
