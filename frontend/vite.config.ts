import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// In development the browser talks only to Vite (localhost:5173); API and SSE requests are
// proxied to Spring Boot, so cookies and CSRF behave exactly as in production, where Spring
// Boot serves this build itself (single origin, no CORS).
const backend = process.env.QUEUELESS_BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend },
      '/actuator': { target: backend },
      '/v3/api-docs': { target: backend },
      '/swagger-ui': { target: backend },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
