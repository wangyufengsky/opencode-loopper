import { existsSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'
const executablePath = process.env.PLAYWRIGHT_CHROME_EXECUTABLE ?? '/usr/bin/chromium'
if (!existsSync(executablePath)) throw new Error(`Local Chromium is required: ${executablePath}`)
export default defineConfig({ testDir: '.', testMatch: '**/*.spec.ts', timeout: 60000, workers: 1, fullyParallel: false,
  forbidOnly: !!process.env.CI, reporter: 'list', use: { ...devices['Desktop Chrome'], baseURL: 'http://127.0.0.1:41775',
    trace: 'retain-on-failure', screenshot: 'only-on-failure', launchOptions: { executablePath } },
  projects: [{ name: 'w5-production-chromium', use: { browserName: 'chromium' } }],
  webServer: { command: 'node e2e/w5/serve.mjs', cwd: '../..', url: 'http://127.0.0.1:41775', reuseExistingServer: false, timeout: 30000 },
})
