import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, ApiError } from './client';
import type {
  Appointment,
  BoardTicket,
  CurrentUser,
  Member,
  MembershipRole,
  NotificationList,
  Organization,
  OrganizationManagement,
  Queue,
  QueueBoard,
  QueueConfig,
  QueueLive,
  QueueStatus,
  Slots,
  Ticket,
  TicketPriority,
} from './types';

/**
 * Query keys. Prefix matching lets one SSE event refresh everything about a queue:
 * invalidating ['queue', id] covers its config, live summary, slots and staff board.
 */
export const keys = {
  me: ['me'] as const,
  organizations: (search: string) => ['organizations', search] as const,
  organization: (slug: string) => ['organization', slug] as const,
  organizationQueues: (slug: string) => ['organization', slug, 'queues'] as const,
  queue: (id: string) => ['queue', id] as const,
  queueLive: (id: string) => ['queue', id, 'live'] as const,
  slots: (id: string, date: string) => ['queue', id, 'slots', date] as const,
  board: (id: string) => ['queue', id, 'board'] as const,
  ticket: (id: string) => ['ticket', id] as const,
  appointment: (id: string) => ['appointment', id] as const,
  myTickets: ['my', 'tickets'] as const,
  myAppointments: ['my', 'appointments'] as const,
  notifications: ['my', 'notifications'] as const,
  admin: (orgId: string) => ['admin', orgId] as const,
  adminQueues: (orgId: string) => ['admin', orgId, 'queues'] as const,
  adminMembers: (orgId: string) => ['admin', orgId, 'members'] as const,
};

/**
 * Called when the signed-in identity changes (login, registration, logout). Drops every cached
 * query except the current user, so no data of a previous user can flash on screen, while the
 * mounted ['me'] query keeps its observers (clearing the whole cache would detach them).
 */
function switchIdentity(client: QueryClient, user: CurrentUser | null) {
  client.removeQueries({ predicate: (query) => query.queryKey[0] !== 'me' });
  client.setQueryData(keys.me, user);
}

/** Refreshes everything that may change when a queue changes (called on SSE events). */
export function invalidateQueue(client: QueryClient, queueId: string) {
  void client.invalidateQueries({ queryKey: keys.queue(queueId) });
  void client.invalidateQueries({ queryKey: ['ticket'] });
  void client.invalidateQueries({ queryKey: ['appointment'] });
  void client.invalidateQueries({ queryKey: ['my'] });
}

// --- identity ---------------------------------------------------------------

export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: async () => {
      try {
        return await api<CurrentUser>('/api/auth/me');
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null;
        throw error;
      }
    },
    staleTime: 60_000,
  });
}

export function useLogin() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { email: string; password: string }) =>
      api<CurrentUser>('/api/auth/login', { method: 'POST', body }),
    onSuccess: (user) => switchIdentity(client, user),
  });
}

export function useRegister() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { email: string; fullName: string; password: string }) =>
      api<CurrentUser>('/api/auth/register', { method: 'POST', body }),
    onSuccess: (user) => switchIdentity(client, user),
  });
}

export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>('/api/auth/logout', { method: 'POST' }),
    onSettled: () => switchIdentity(client, null),
  });
}

// --- discovery --------------------------------------------------------------

export function useOrganizations(search: string) {
  return useQuery({
    queryKey: keys.organizations(search),
    queryFn: () => api<Organization[]>(`/api/organizations${search ? `?search=${encodeURIComponent(search)}` : ''}`),
    placeholderData: (previous) => previous,
  });
}

export function useOrganization(slug: string) {
  return useQuery({ queryKey: keys.organization(slug), queryFn: () => api<Organization>(`/api/organizations/${slug}`) });
}

export function useOrganizationQueues(slug: string) {
  return useQuery({
    queryKey: keys.organizationQueues(slug),
    queryFn: () => api<Queue[]>(`/api/organizations/${slug}/queues`),
  });
}

export function useQueue(queueId: string) {
  return useQuery({ queryKey: keys.queue(queueId), queryFn: () => api<Queue>(`/api/queues/${queueId}`) });
}

export function useQueueLive(queueId: string) {
  return useQuery({
    queryKey: keys.queueLive(queueId),
    queryFn: () => api<QueueLive>(`/api/queues/${queueId}/live`),
  });
}

export function useSlots(queueId: string, date: string | null) {
  return useQuery({
    queryKey: keys.slots(queueId, date ?? 'today'),
    queryFn: () => api<Slots>(`/api/queues/${queueId}/slots${date ? `?date=${date}` : ''}`),
    placeholderData: (previous) => previous,
  });
}

// --- customer ---------------------------------------------------------------

export function useMyTickets(enabled = true) {
  return useQuery({ queryKey: keys.myTickets, queryFn: () => api<Ticket[]>('/api/me/tickets'), enabled });
}

export function useMyAppointments(enabled = true) {
  return useQuery({
    queryKey: keys.myAppointments,
    queryFn: () => api<Appointment[]>('/api/me/appointments'),
    enabled,
  });
}

export function useTicket(ticketId: string) {
  return useQuery({ queryKey: keys.ticket(ticketId), queryFn: () => api<Ticket>(`/api/tickets/${ticketId}`) });
}

export function useAppointment(appointmentId: string) {
  return useQuery({
    queryKey: keys.appointment(appointmentId),
    queryFn: () => api<Appointment>(`/api/appointments/${appointmentId}`),
  });
}

export function useJoinQueue(queueId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api<Ticket>(`/api/queues/${queueId}/tickets`, { method: 'POST' }),
    onSuccess: () => invalidateQueue(client, queueId),
  });
}

export function useBookAppointment(queueId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ scheduledAt, idempotencyKey }: { scheduledAt: string; idempotencyKey: string }) =>
      api<Appointment>(`/api/queues/${queueId}/appointments`, {
        method: 'POST',
        body: { scheduledAt },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    onSettled: () => invalidateQueue(client, queueId),
  });
}

export function useCancelAppointment() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (appointment: { id: string; queueId: string }) =>
      api<Appointment>(`/api/appointments/${appointment.id}/cancel`, { method: 'POST' }),
    onSettled: (_data, _error, appointment) => invalidateQueue(client, appointment.queueId),
  });
}

export function useCheckIn() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (appointment: { id: string; queueId: string }) =>
      api<Ticket>(`/api/appointments/${appointment.id}/check-in`, { method: 'POST' }),
    onSettled: (_data, _error, appointment) => invalidateQueue(client, appointment.queueId),
  });
}

export function useCancelTicket() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (ticket: { id: string; queueId: string }) =>
      api<Ticket>(`/api/tickets/${ticket.id}/cancel`, { method: 'POST' }),
    onSettled: (_data, _error, ticket) => invalidateQueue(client, ticket.queueId),
  });
}

// --- notifications ----------------------------------------------------------

export function useNotifications(enabled = true) {
  return useQuery({
    queryKey: keys.notifications,
    queryFn: () => api<NotificationList>('/api/me/notifications?limit=50'),
    enabled,
  });
}

export function useMarkNotificationRead() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api(`/api/me/notifications/${id}/read`, { method: 'POST' }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.notifications }),
  });
}

export function useMarkAllNotificationsRead() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api('/api/me/notifications/read-all', { method: 'POST' }),
    onSettled: () => client.invalidateQueries({ queryKey: keys.notifications }),
  });
}

// --- staff ------------------------------------------------------------------

export function useBoard(queueId: string) {
  return useQuery({
    queryKey: keys.board(queueId),
    queryFn: () => api<QueueBoard>(`/api/staff/queues/${queueId}/board`),
  });
}

function useStaffMutation<TVariables>(queueId: string, request: (variables: TVariables) => Promise<unknown>) {
  const client = useQueryClient();
  return useMutation({ mutationFn: request, onSettled: () => invalidateQueue(client, queueId) });
}

export function useStaffActions(queueId: string) {
  return {
    callNext: useStaffMutation(queueId, () =>
      api<BoardTicket>(`/api/staff/queues/${queueId}/call-next`, { method: 'POST' })),
    start: useStaffMutation(queueId, (ticketId: string) =>
      api<BoardTicket>(`/api/staff/tickets/${ticketId}/start`, { method: 'POST' })),
    complete: useStaffMutation(queueId, (ticketId: string) =>
      api<BoardTicket>(`/api/staff/tickets/${ticketId}/complete`, { method: 'POST' })),
    noShow: useStaffMutation(queueId, (ticketId: string) =>
      api<BoardTicket>(`/api/staff/tickets/${ticketId}/no-show`, { method: 'POST' })),
    priority: useStaffMutation(queueId, ({ ticketId, priority }: { ticketId: string; priority: TicketPriority }) =>
      api<BoardTicket>(`/api/staff/tickets/${ticketId}/priority`, { method: 'PATCH', body: { priority } })),
    cancel: useStaffMutation(queueId, (ticketId: string) =>
      api<Ticket>(`/api/tickets/${ticketId}/cancel`, { method: 'POST' })),
    checkIn: useStaffMutation(queueId, (appointmentId: string) =>
      api<Ticket>(`/api/appointments/${appointmentId}/check-in`, { method: 'POST' })),
    status: useStaffMutation(queueId, (status: QueueStatus) =>
      api<Queue>(`/api/staff/queues/${queueId}/status`, { method: 'POST', body: { status } })),
    guest: useStaffMutation(queueId, (body: { guestName: string; priority: TicketPriority }) =>
      api<BoardTicket>(`/api/staff/queues/${queueId}/walk-ins`, { method: 'POST', body })),
  };
}

// --- organization administration -------------------------------------------

export function useManagement(orgId: string) {
  return useQuery({
    queryKey: keys.admin(orgId),
    queryFn: () => api<OrganizationManagement>(`/api/admin/organizations/${orgId}`),
  });
}

export function useManagedQueues(orgId: string) {
  return useQuery({
    queryKey: keys.adminQueues(orgId),
    queryFn: () => api<Queue[]>(`/api/admin/organizations/${orgId}/queues`),
  });
}

export function useMembers(orgId: string, enabled = true) {
  return useQuery({
    queryKey: keys.adminMembers(orgId),
    queryFn: () => api<Member[]>(`/api/admin/organizations/${orgId}/members`),
    enabled,
  });
}

export function useSaveQueue(orgId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ queueId, config }: { queueId?: string; config: QueueConfig }) =>
      queueId
        ? api<Queue>(`/api/admin/queues/${queueId}`, { method: 'PUT', body: config })
        : api<Queue>(`/api/admin/organizations/${orgId}/queues`, { method: 'POST', body: config }),
    onSuccess: (queue) => {
      void client.invalidateQueries({ queryKey: keys.admin(orgId) });
      void client.invalidateQueries({ queryKey: keys.queue(queue.id) });
      void client.invalidateQueries({ queryKey: ['organization'] });
    },
  });
}

export function useMemberActions(orgId: string) {
  const client = useQueryClient();
  const onSettled = () => client.invalidateQueries({ queryKey: keys.admin(orgId) });
  return {
    add: useMutation({
      mutationFn: (body: { email: string; role: MembershipRole }) =>
        api<Member>(`/api/admin/organizations/${orgId}/members`, { method: 'POST', body }),
      onSettled,
    }),
    changeRole: useMutation({
      mutationFn: ({ membershipId, role }: { membershipId: string; role: MembershipRole }) =>
        api<Member>(`/api/admin/organizations/${orgId}/members/${membershipId}`, { method: 'PATCH', body: { role } }),
      onSettled,
    }),
    remove: useMutation({
      mutationFn: (membershipId: string) =>
        api<void>(`/api/admin/organizations/${orgId}/members/${membershipId}`, { method: 'DELETE' }),
      onSettled,
    }),
  };
}

export function useUpdateOrganization(orgId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; description: string | null }) =>
      api<OrganizationManagement>(`/api/admin/organizations/${orgId}`, { method: 'PATCH', body }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.admin(orgId) });
      void client.invalidateQueries({ queryKey: keys.me });
      void client.invalidateQueries({ queryKey: ['organization'] });
    },
  });
}

export function useCreateOrganization() {
  return useMutation({
    mutationFn: (body: { name: string; slug: string; description: string; timezone: string; adminEmail: string }) =>
      api<Organization>('/api/platform/organizations', { method: 'POST', body }),
  });
}
