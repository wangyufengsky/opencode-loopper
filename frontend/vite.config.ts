import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { skinBootstrap, skinStyles } from './src/themes/compile'

export default defineConfig({
  plugins: [react(), {
    name: 'react-executable-inventory',
    generateBundle() {
      const root = fileURLToPath(new URL('.', import.meta.url)).replaceAll('\\', '/')
      const modules = [...this.getModuleIds()].map(id => id.replaceAll('\\', '/').replaceAll(root, '').replaceAll('\0', 'virtual:')).sort()
      this.emitFile({ type: 'asset', fileName: 'react-module-inventory.json', source: JSON.stringify({ modules }, null, 2) })
    },
  }, {
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
    setupFiles: ['./src/test/browserFetchEnvironment.ts', './src/test/reactFlowEnvironment.ts'],
    globals: true,
    // Bound concurrent jsdom instances so full verification does not exhaust the host.
    maxWorkers: 4,
    include: ['src/**/*.spec.ts', 'src/**/*.spec.tsx'],
  },
})
