import { existsSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'

const chromeExecutable = process.env.PLAYWRIGHT_CHROME_EXECUTABLE
  ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

if (!existsSync(chromeExecutable)) {
  throw new Error(`Local Chrome is required for E2E: ${chromeExecutable}`)
}

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  forbidOnly: Boolean(process.env.CI),
  fullyParallel: false,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:41773',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{
    name: 'local-chrome',
    use: {
      browserName: 'chromium',
      // executablePath deliberately selects the operator-installed Chrome. Do
      // not set a Playwright channel: that could select a bundled browser.
      launchOptions: { executablePath: chromeExecutable },
    },
  }],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 41773 --strictPort',
    url: 'http://127.0.0.1:41773',
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
