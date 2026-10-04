import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'
import { localOrigin } from './environment'

const spring = localOrigin(process.env.BACKEND_INTEGRATION_SPRING_URL, 'BACKEND_INTEGRATION_SPRING_URL')
const frontend = fileURLToPath(new URL('../../', import.meta.url))
const port = Number(process.env.BACKEND_INTEGRATION_PREVIEW_PORT)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('BACKEND_INTEGRATION_PREVIEW_PORT must be an explicit unprivileged port')

/** Official Vite production preview proxy only; no shared build configuration changes. */
export default defineConfig({
  root: frontend, build: { outDir: 'dist' },
  preview: { host: '127.0.0.1', port, strictPort: true,
    proxy: { '/api': { target: spring, changeOrigin: false }, '/actuator': { target: spring, changeOrigin: false } },
  },
})
