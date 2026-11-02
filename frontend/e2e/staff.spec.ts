import { expect, test } from '@playwright/test';
import { apiPost, DEMO_PASSWORD, loginViaUi, organizationIdOf, registerViaApi } from './helpers';

test('staff call a customer and the customer sees it live, without reloading', async ({ browser }) => {
  // The clinic admin (who may also operate queues) prepares a dedicated, empty queue.
  const staffContext = await browser.newContext();
  const staffPage = await staffContext.newPage();
  await loginViaUi(staffPage, 'admin@citycare.example', DEMO_PASSWORD, '/admin');
  const organizationId = await organizationIdOf(staffContext, 'city-care-clinic');
  const created = await apiPost(staffContext, `/api/admin/organizations/${organizationId}/queues`, {
    name: `E2E counter ${Date.now()}`, description: 'Created by the E2E suite', tokenPrefix: 'E',
    openingTime: '00:00', closingTime: '23:45', slotDurationMinutes: 15, capacityPerSlot: 1,
    defaultServiceMinutes: 5, bookingEnabled: false,
  });
  expect(created.status()).toBe(201);
  const queue = await created.json();
  expect((await apiPost(staffContext, `/api/staff/queues/${queue.id}/status`, { status: 'OPEN' })).status()).toBe(200);

  // A customer in another browser joins the queue and watches their ticket.
  const customerContext = await browser.newContext();
  const customerPage = await customerContext.newPage();
  await registerViaApi(customerContext, 'Live Customer');
  await customerPage.goto(`/queues/${queue.id}`);
  await customerPage.getByRole('button', { name: 'Get a token' }).click();
  await expect(customerPage.getByTestId('token')).toHaveText('E-001');
  await expect(customerPage.getByText('#1')).toBeVisible();
  await expect(customerPage.getByText('Live', { exact: true })).toBeVisible();

  // The staff board shows the customer; staff call them.
  await staffPage.goto(`/staff/queues/${queue.id}`);
  await expect(staffPage.getByRole('cell', { name: 'Live Customer' })).toBeVisible();
  await staffPage.getByRole('button', { name: 'Call next' }).click();
  await expect(staffPage.getByText('E-001').first()).toBeVisible();

  // Server-Sent Events update the customer's page and show a notification toast.
  await expect(customerPage.getByText(/your turn!/i)).toBeVisible();
  await expect(customerPage.getByText("It's your turn")).toBeVisible();

  await staffPage.getByRole('button', { name: 'Start service' }).click();
  await staffPage.getByRole('button', { name: 'Complete' }).click();
  await expect(staffPage.getByText('The counter is free.')).toBeVisible();
  await expect(customerPage.getByText('Service completed')).toBeVisible();

  // The notification is stored, so it is still listed after navigating away.
  await customerPage.goto('/notifications');
  await expect(customerPage.getByText("It's your turn")).toBeVisible();

  await apiPost(staffContext, `/api/staff/queues/${queue.id}/status`, { status: 'CLOSED' });
  await staffContext.close();
  await customerContext.close();
});

test('staff of another organization cannot open the board', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  const queues = await (await context.request.get('/api/organizations/city-care-clinic/queues')).json();
  await loginViaUi(page, 'staff@northbridge.example', DEMO_PASSWORD, '/staff');
  await page.goto(`/staff/queues/${queues[0].id}`);
  await expect(page.getByText(/do not have permission/)).toBeVisible();
  await context.close();
});
