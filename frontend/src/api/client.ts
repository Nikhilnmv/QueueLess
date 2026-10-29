import type { ProblemDetail } from './types';

/** An error response from the API (RFC 9457 problem detail). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed (${problem.status})`);
    this.name = 'ApiError';
    this.status = problem.status;
    this.code = problem.code ?? 'UNKNOWN';
    this.problem = problem;
  }

  /** Field validation messages keyed by field name. */
  get fieldErrors(): Record<string, string> {
    const result: Record<string, string> = {};
    for (const error of this.problem.errors ?? []) {
      result[error.field] ??= error.message;
    }
    return result;
  }
}

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const cookie = document.cookie.split('; ').find((c) => c.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined;
}

/**
 * The server issues the XSRF-TOKEN cookie on any response. If a write happens before the first
 * read (e.g. a direct login), fetch once to obtain it.
 */
async function ensureCsrfToken(): Promise<string | undefined> {
  if (!readCookie('XSRF-TOKEN')) {
    await fetch('/api/auth/me', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
  }
  return readCookie('XSRF-TOKEN');
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

/**
 * Fetch wrapper used by every API call: same-origin session cookie, CSRF header on writes
 * (double-submit cookie), JSON in/out and typed errors. Credentials are never stored in
 * localStorage: the session lives in an HTTP-only cookie the script cannot read.
 */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (method !== 'GET') {
    const token = await ensureCsrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', detail: 'Cannot reach the server. Check your connection.' });
  }

  if (response.status === 204) return undefined as T;
  const text = await response.text();
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = undefined;
    }
  }
  if (!response.ok) {
    const problem: ProblemDetail =
      data && typeof data === 'object'
        ? { ...(data as ProblemDetail), status: response.status }
        : { status: response.status, code: response.status >= 500 ? 'INTERNAL_ERROR' : 'UNKNOWN', detail: response.statusText };
    throw new ApiError(problem);
  }
  return data as T;
}

/** Human-readable message for any error thrown by a query or mutation. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 503 && !error.problem.detail) return 'The server is waking up or busy. Please retry in a moment.';
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return 'Something went wrong.';
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code);
}
