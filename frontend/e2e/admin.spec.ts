import { expect, test } from '@playwright/test';
import { DEMO_PASSWORD, loginViaUi, registerViaApi } from './helpers';

test('an administrator creates a queue and manages members', async ({ browser, page }) => {
  // Someone registers on their own; the admin then gives them the STAFF role.
  const helper = await browser.newContext();
  const newStaffEmail = await registerViaApi(helper, 'E2E New Staff');
  await helper.close();

  await loginViaUi(page, 'admin@citycare.example', DEMO_PASSWORD, '/admin');
  await page.getByRole('link', { name: /City Care Clinic/ }).click();
  await expect(page.getByText('your role: admin')).toBeVisible();

  // Create a queue through the dialog.
  const queueName = `E2E Pharmacy ${Date.now()}`;
  await page.getByRole('button', { name: 'New queue' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name').fill(queueName);
  await dialog.getByLabel('Token prefix').fill('ph');
  await dialog.getByRole('button', { name: 'Create queue' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(queueName)).toBeVisible();

  // Add the registered user as staff.
  await page.getByRole('tab', { name: 'Members' }).click();
  await page.getByLabel('Email').fill(newStaffEmail);
  await page.getByRole('button', { name: 'Add' }).click();
  const row = page.getByRole('row').filter({ hasText: newStaffEmail });
  await expect(row).toBeVisible();
  await expect(row.getByRole('combobox')).toHaveValue('STAFF');

  // The organization's only administrator cannot be demoted.
  await page.getByLabel('Role of City Care Admin').selectOption('STAFF');
  await expect(page.getByText('An organization must keep at least one administrator')).toBeVisible();
  await expect(page.getByLabel('Role of City Care Admin')).toHaveValue('ADMIN');
});
