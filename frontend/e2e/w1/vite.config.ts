import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

/** Isolated test entry: no Vue plugin, product route/history, proxy or backend. */
export default defineConfig({
  root: fileURLToPath(new URL('../../', import.meta.url)),
  plugins: [react()],
  resolve: { alias: { '@': fileURLToPath(new URL('../../src', import.meta.url)) } },
  server: { host: '127.0.0.1', port: 41784, strictPort: true },
  build: {
    outDir: fileURLToPath(new URL('../../../../react-full-w1-evidence/build', import.meta.url)),
    emptyOutDir: false,
    rollupOptions: { input: fileURLToPath(new URL('./preview.html', import.meta.url)) },
  },
})
