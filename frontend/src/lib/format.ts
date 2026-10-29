import type { AppointmentStatus, QueueStatus, TicketPriority, TicketSource, TicketStatus } from '../api/types';

// All business times are shown in the organization's time zone, never the browser's,
// because appointments and service days are defined in that zone.

export function formatTime(iso: string | null | undefined, timeZone: string): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat(undefined, { timeZone, hour: '2-digit', minute: '2-digit' }).format(new Date(iso));
}

export function formatDateTime(iso: string | null | undefined, timeZone: string): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat(undefined, {
    timeZone,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}

/** A LocalDate ("2026-03-10") rendered as a calendar date (no time-zone shifting). */
export function formatLocalDate(date: string, style: 'short' | 'long' = 'long'): string {
  const [year, month, day] = date.split('-').map(Number);
  return new Intl.DateTimeFormat(undefined, {
    timeZone: 'UTC',
    weekday: style === 'long' ? 'long' : 'short',
    day: 'numeric',
    month: style === 'long' ? 'long' : 'short',
  }).format(new Date(Date.UTC(year, month - 1, day)));
}

/** "09:00:00" -> "09:00". */
export function formatLocalTime(time: string): string {
  return time.slice(0, 5);
}

export function relativeTime(iso: string): string {
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  const abs = Math.abs(seconds);
  if (abs < 60) return formatter.format(seconds, 'second');
  if (abs < 3600) return formatter.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return formatter.format(Math.round(seconds / 3600), 'hour');
  return formatter.format(Math.round(seconds / 86400), 'day');
}

export function minutesLabel(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return '—';
  if (minutes <= 0) return 'Now';
  if (minutes < 60) return `~${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `~${hours} h` : `~${hours} h ${rest} min`;
}

/** Calendar date (YYYY-MM-DD) of "now" in a time zone. */
export function todayIn(timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(new Date());
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

export function addDays(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number);
  const next = new Date(Date.UTC(year, month - 1, day + days));
  return next.toISOString().slice(0, 10);
}

export const queueStatusLabel: Record<QueueStatus, string> = {
  OPEN: 'Open',
  PAUSED: 'Paused',
  CLOSED: 'Closed',
};

export const ticketStatusLabel: Record<TicketStatus, string> = {
  BOOKED: 'Booked',
  WAITING: 'Waiting',
  CALLED: 'Called',
  SERVING: 'Being served',
  COMPLETED: 'Completed',
  NO_SHOW: 'No-show',
  CANCELLED: 'Cancelled',
};

export const appointmentStatusLabel: Record<AppointmentStatus, string> = {
  BOOKED: 'Booked',
  CHECKED_IN: 'Checked in',
  COMPLETED: 'Completed',
  NO_SHOW: 'No-show',
  CANCELLED: 'Cancelled',
};

export const priorityLabel: Record<TicketPriority, string> = {
  HIGH: 'High',
  NORMAL: 'Normal',
  LOW: 'Low',
};

export const sourceLabel: Record<TicketSource, string> = {
  APPOINTMENT: 'Appointment',
  WALK_IN: 'Walk-in',
  GUEST_WALK_IN: 'Desk walk-in',
};

/** Random UUID for the Idempotency-Key header (available on HTTPS pages and localhost). */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
