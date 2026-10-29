import type { CurrentUser, MembershipSummary } from '../api/types';

// These helpers only decide what to *show*. Every protected operation is authorized again by
// the server against the organization that owns the resource.

export function staffMemberships(user: CurrentUser | null | undefined): MembershipSummary[] {
  return user?.memberships.filter((m) => m.role === 'STAFF' || m.role === 'ADMIN') ?? [];
}

export function adminMemberships(user: CurrentUser | null | undefined): MembershipSummary[] {
  return user?.memberships.filter((m) => m.role === 'ADMIN') ?? [];
}
