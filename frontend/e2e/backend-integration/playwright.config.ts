import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig, devices } from '@playwright/test'
import { integrationEnvironment } from './environment'

const environment = integrationEnvironment()
const executablePath = process.env.PLAYWRIGHT_CHROME_EXECUTABLE ?? '/usr/bin/chromium'
if (!existsSync(executablePath)) throw new Error(`Local Chromium is required: ${executablePath}`)

export default defineConfig({
  testDir: '.', testMatch: '**/*.spec.ts', workers: 1, retries: 0, fullyParallel: false, forbidOnly: true,
  timeout: 60_000, expect: { timeout: 10_000 },
  reporter: [['list'], ['json', { outputFile: join(environment.evidenceDir, 'browser.json') }]],
  outputDir: join(environment.evidenceDir, 'results'),
  use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 },
    baseURL: environment.baseURL, trace: 'retain-on-failure', screenshot: 'only-on-failure',
    launchOptions: { executablePath },
  },
  projects: [{ name: 'real-spring-desktop-chromium', use: { browserName: 'chromium' } }],
  // No webServer: Spring / production preview are started and stopped only by the lead.
})
