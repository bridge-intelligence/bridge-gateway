import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/gateway': {
        target: 'http://localhost:4000',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:4000',
        changeOrigin: true,
      },
      '/api/v1/login': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api/v1/register': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api/v1/userinfo': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
