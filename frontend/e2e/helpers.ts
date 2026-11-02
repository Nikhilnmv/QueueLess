import { expect, type APIResponse, type BrowserContext, type Page } from '@playwright/test';

export const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD ?? 'Password123!';

export function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1_000_000)}@example.com`;
}

export async function loginViaUi(page: Page, email: string, password: string, next: string): Promise<void> {
  await page.goto(`/login?next=${encodeURIComponent(next)}`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.waitForURL((url) => url.pathname === next);
}

/** Calls the API with the context's session cookie and CSRF header, exactly like the SPA. */
export async function apiPost(context: BrowserContext, path: string, body?: unknown): Promise<APIResponse> {
  await context.request.get('/api/auth/me'); // issues the XSRF-TOKEN cookie if missing
  const token = (await context.cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? '';
  return context.request.post(path, { data: body, headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) } });
}

export async function registerViaApi(context: BrowserContext, fullName: string): Promise<string> {
  const email = uniqueEmail('e2e');
  const response = await apiPost(context, '/api/auth/register', { email, fullName, password: 'e2e-password-1' });
  expect(response.status()).toBe(201);
  return email;
}

export async function organizationIdOf(context: BrowserContext, slug: string): Promise<string> {
  const me = await (await context.request.get('/api/auth/me')).json();
  return me.memberships.find((m: { organizationSlug: string }) => m.organizationSlug === slug).organizationId;
}
