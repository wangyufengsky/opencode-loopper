import { existsSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'

const chromeExecutable = process.env.PLAYWRIGHT_CHROME_EXECUTABLE ?? '/usr/bin/chromium'
if (!existsSync(chromeExecutable)) throw new Error(`Local Chromium is required: ${chromeExecutable}`)

/** W2 verifies the actual built SPA; dev HMR is outside the production resource contract. */
export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  timeout: 45_000,
  forbidOnly: Boolean(process.env.CI),
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:41773',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: { executablePath: chromeExecutable },
  },
  projects: [{ name: 'production-chromium', use: { browserName: 'chromium' } }],
  webServer: {
    command: 'node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 41773 --strictPort',
    // Playwright resolves cwd relative to this config, not the invoking shell.
    cwd: '../..',
    url: 'http://127.0.0.1:41773',
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
