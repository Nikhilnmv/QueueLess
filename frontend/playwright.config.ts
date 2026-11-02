import { defineConfig, devices } from '@playwright/test';

// End-to-end tests run against the packaged application (Spring Boot serving the React build)
// with the dev profile's demo data. Set E2E_BASE_URL to test another deployment instead; then
// E2E_DEMO_PASSWORD must hold that deployment's demo password (QUEUELESS_SEED_PASSWORD).
const externalBaseUrl = process.env.E2E_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  // The tests share the seeded demo organizations, so they run one at a time.
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: externalBaseUrl ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: externalBaseUrl
    ? undefined
    : {
        // Build first: npm run build (frontend) and ./mvnw package (backend).
        command: 'java -jar ../backend/target/queueless.jar --spring.profiles.active=dev',
        url: 'http://localhost:8080/actuator/health/liveness',
        reuseExistingServer: true,
        timeout: 120_000,
      },
});
