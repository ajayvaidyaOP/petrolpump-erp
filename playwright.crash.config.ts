import { defineConfig } from '@playwright/test';

/**
 * Backend-independent regression run for the render-crash fix.
 * The spec mocks every /api/v1 call with page.route, so only the Vite dev server
 * is required — no Java API and no MySQL. Run with:
 *   npx playwright install chromium
 *   npx playwright test --config playwright.crash.config.ts
 */
const webPort = 5175;
const origin = `http://127.0.0.1:${webPort}`;
const browserExecutable = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH;

export default defineConfig({
  testDir: './frontend/e2e',
  testMatch: /crash-resilience\.spec\.ts/,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: origin,
    actionTimeout: 12_000,
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    launchOptions: {
      ...(browserExecutable ? { executablePath: browserExecutable } : {}),
      args: browserExecutable ? ['--no-sandbox', '--no-zygote'] : [],
    },
  },
  webServer: {
    command: `npm run dev -w @petroflow/web -- --port ${webPort} --strictPort`,
    url: origin,
    timeout: 90_000,
    reuseExistingServer: false,
    env: { PETROFLOW_HMR_CLIENT_PORT: String(webPort) },
  },
});
