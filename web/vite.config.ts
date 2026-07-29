import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8767',
      '/ws': { target: 'ws://localhost:8767', ws: true },
    },
  },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
})
