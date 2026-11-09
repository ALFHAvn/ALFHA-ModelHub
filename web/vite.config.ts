import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    cors: false,
    proxy: {
      '^/api/(health|models|generate|compare|runs)(\\?|$)': 'http://127.0.0.1:8080',
      '^/api/workflows/proposal-review(\\?|$)': 'http://127.0.0.1:8080',
      '^/api/benchmarks/(coding-reports|coding-cohort|[0-9a-fA-F-]{36}(/coding-peers)?)(\\?|$)': 'http://127.0.0.1:8080',
    },
  },
});
