import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { skinBootstrap, skinStyles } from './src/themes/compile'

export default defineConfig({
  plugins: [vue(), {
    name: 'loopper-skins',
    transformIndexHtml() {
      return [
        { tag: 'style', attrs: { id: 'loopper-skins' }, children: skinStyles(), injectTo: 'head' },
        { tag: 'script', children: skinBootstrap(), injectTo: 'head' },
      ]
    },
  }],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/actuator': 'http://127.0.0.1:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    // Bound concurrent jsdom instances so full verification does not exhaust the host.
    maxWorkers: 4,
    include: ['src/**/*.spec.ts'],
  },
})
