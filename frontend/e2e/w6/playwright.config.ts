import { existsSync } from 'node:fs'
import { defineConfig, devices } from '@playwright/test'
const executablePath=process.env.PLAYWRIGHT_CHROME_EXECUTABLE??'/usr/bin/chromium'
if(!existsSync(executablePath))throw new Error('Local Chromium is required')
export default defineConfig({testDir:'.',testMatch:'production-routes.spec.ts',timeout:60000,workers:1,fullyParallel:false,forbidOnly:!!process.env.CI,reporter:[['list'],['json',{outputFile:process.env.CANVAS_W6_BROWSER_JSON??'/tmp/w6-browser.json'}]],use:{...devices['Desktop Chrome'],viewport:{width:1440,height:1000},baseURL:'http://127.0.0.1:41776',trace:'retain-on-failure',screenshot:'only-on-failure',launchOptions:{executablePath}},projects:[{name:'w6-production-chromium',use:{browserName:'chromium'}}],webServer:{command:'node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 41776 --strictPort',cwd:'../..',url:'http://127.0.0.1:41776',reuseExistingServer:false,timeout:30000}})
