import { defineConfig } from 'vite'
import base from '../../vite.config'

/** Test-only mock transport. Unmatched API requests cannot reach a live provider.
 * This does not change the application development or deployment configuration.
 */
export default defineConfig({
  ...base,
  server: { ...base.server, host: '127.0.0.1', port: 41773, strictPort: true, proxy: {} },
  plugins: [...(base.plugins ?? []), {
    name: 'w7-unmatched-mock-api',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        if (!/^\/(api|actuator)(\/|\?|$)/.test(request.url ?? '')) return next()
        response.statusCode = 501
        response.setHeader('Content-Type', 'application/json')
        response.end(JSON.stringify({ detail: 'W7 模拟接口未配置；本测试未连接真实后端。' }))
      })
    },
  }],
})
