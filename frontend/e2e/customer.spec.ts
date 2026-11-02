import { expect, test } from '@playwright/test';
import { uniqueEmail } from './helpers';

test('a customer registers, books and cancels an appointment, then joins a queue', async ({ page }) => {
  // Register (the account starts a session immediately).
  await page.goto('/register');
  await page.getByLabel('Full name').fill('E2E Customer');
  await page.getByLabel('Email').fill(uniqueEmail('e2e-customer'));
  await page.getByLabel('Password', { exact: true }).fill('e2e-password-1');
  await page.getByLabel('Confirm password').fill('e2e-password-1');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { name: 'Hello, E2E Customer' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();

  // Discover an organization and a queue.
  await page.getByRole('main').getByRole('link', { name: 'Find a service' }).click();
  await page.getByRole('link', { name: /City Care Clinic/ }).click();
  await page.getByRole('link', { name: /General OPD/ }).click();
  await expect(page.getByRole('heading', { name: /General OPD/ })).toBeVisible();
  const queueUrl = page.url();

  // Book the first free slot tomorrow.
  await page.getByRole('link', { name: 'Choose a slot' }).click();
  await page.getByRole('option').nth(1).click();
  await page.locator('button[aria-pressed]:not([disabled])').first().click();
  await page.getByRole('button', { name: 'Confirm booking' }).click();
  await expect(page.getByText('Booking confirmed')).toBeVisible();
  await expect(page.getByRole('heading', { name: /Appointment QL-[0-9A-Z]{6}/ })).toBeVisible();
  await expect(page.getByText(/^G-\d{3}$/).first()).toBeVisible();

  // It appears on the dashboard.
  await page.getByRole('link', { name: 'Back to my queue' }).click();
  await expect(page.getByRole('heading', { name: 'Upcoming appointments', exact: true })).toBeVisible();
  await page.getByRole('link', { name: /General OPD/ }).first().click();

  // Cancel it (with confirmation); the slot is released.
  await page.getByRole('button', { name: 'Cancel appointment' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel appointment' }).click();
  await expect(page.getByText('Cancelled').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel appointment' })).toHaveCount(0);

  // Join today's line as a walk-in and see the live position.
  await page.goto(queueUrl);
  await page.getByRole('button', { name: 'Get a token' }).click();
  await expect(page).toHaveURL(/\/tickets\//);
  await expect(page.getByTestId('token')).toHaveText(/^G-\d{3}$/);
  await expect(page.getByText('Your position')).toBeVisible();
  await expect(page.getByText(/^#\d+$/)).toBeVisible();
});

test('protected pages send anonymous visitors to the login page', async ({ page }) => {
  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login\?next=%2Fdashboard$/);
  await expect(page.getByRole('heading', { name: 'Log in' })).toBeVisible();
});

test('unknown routes show the not-found page', async ({ page }) => {
  await page.goto('/this/page/does/not/exist');
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
});
