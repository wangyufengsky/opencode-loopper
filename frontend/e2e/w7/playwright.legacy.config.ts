import { existsSync, readFileSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'

const baseline = JSON.parse(readFileSync(new URL('../../../docs/design/react-full-migration/evidence/w7/test-discovery-baseline.json', import.meta.url), 'utf8')) as { cases: { title: string; scope: string }[] }
const outOfScope = [...new Set(baseline.cases.filter(row => row.scope === 'OUT_OF_SCOPE_NARROW').map(row => row.title))]
const escapeRegex = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const desktopOnly = new RegExp(`(?:^|\\s)(?:${outOfScope.map(escapeRegex).join('|')})$`)

const executablePath = process.env.PLAYWRIGHT_CHROME_EXECUTABLE ?? '/usr/bin/chromium'
if (!existsSync(executablePath)) throw new Error(`Local Chromium is required: ${executablePath}`)

/** Historical desktop contracts use the lead-owned development fixture server.
 * Production waves are run separately against their frozen production bundles.
 * No retries: a diagnostic rerun must be recorded separately from the first run.
 */
export default defineConfig({
  testDir: '..',
  grepInvert: desktopOnly,
  testMatch: /\/e2e\/[^/]+\.spec\.ts$/,
  timeout: 30_000,
  workers: 1,
  retries: 0,
  fullyParallel: false,
  forbidOnly: true,
  reporter: [['list'], ['json', { outputFile: process.env.W7_BROWSER_JSON ?? '/tmp/w7-legacy-browser.json' }]],
  outputDir: process.env.W7_BROWSER_OUTPUT ?? '/tmp/w7-legacy-results',
  use: {
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 1000 },
    baseURL: 'http://127.0.0.1:41773',
    launchOptions: { executablePath },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'w7-legacy-desktop-chromium', use: { browserName: 'chromium' } }],
})
