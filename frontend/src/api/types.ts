// TypeScript mirrors of the backend DTOs (see docs/api-documentation.md).
// Instants are ISO-8601 strings in UTC; LocalDate is "YYYY-MM-DD"; LocalTime is "HH:mm[:ss]".

export type MembershipRole = 'CUSTOMER' | 'STAFF' | 'ADMIN';
export type QueueStatus = 'OPEN' | 'PAUSED' | 'CLOSED';
export type TicketStatus = 'BOOKED' | 'WAITING' | 'CALLED' | 'SERVING' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED';
export type TicketPriority = 'HIGH' | 'NORMAL' | 'LOW';
export type TicketSource = 'APPOINTMENT' | 'WALK_IN' | 'GUEST_WALK_IN';
export type AppointmentStatus = 'BOOKED' | 'CHECKED_IN' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED';
export type EstimateBasis = 'HISTORY' | 'DEFAULT';
export type NotificationType =
  | 'APPOINTMENT_CONFIRMED'
  | 'APPOINTMENT_CANCELLED'
  | 'TICKET_CANCELLED'
  | 'TURN_APPROACHING'
  | 'TURN_CALLED'
  | 'MARKED_NO_SHOW';

export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: { field: string; message: string }[];
  [key: string]: unknown;
}

export interface MembershipSummary {
  organizationId: string;
  organizationName: string;
  organizationSlug: string;
  role: MembershipRole;
}

export interface CurrentUser {
  id: string;
  email: string;
  fullName: string;
  platformAdmin: boolean;
  memberships: MembershipSummary[];
}

export interface Organization {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  timezone: string;
}

export interface Queue {
  id: string;
  organizationId: string;
  organizationName: string;
  organizationSlug: string;
  timezone: string;
  name: string;
  description: string | null;
  tokenPrefix: string;
  status: QueueStatus;
  bookingEnabled: boolean;
  openingTime: string;
  closingTime: string;
  slotDurationMinutes: number;
  capacityPerSlot: number;
  defaultServiceMinutes: number;
  stateVersion: number;
}

export interface QueueConfig {
  name: string;
  description: string | null;
  tokenPrefix: string;
  openingTime: string;
  closingTime: string;
  slotDurationMinutes: number;
  capacityPerSlot: number;
  defaultServiceMinutes: number;
  bookingEnabled: boolean;
}

export interface NowServing {
  tokenNumber: number;
  displayToken: string;
  status: TicketStatus;
}

export interface QueueLive {
  queueId: string;
  status: QueueStatus;
  serviceDate: string;
  timezone: string;
  nowServing: NowServing | null;
  waitingCount: number;
  estimatedWaitMinutesForNewTicket: number;
  averageServiceMinutes: number;
  estimateBasis: EstimateBasis;
  estimateSamples: number;
  version: number;
}

export interface LiveTicketInfo {
  queueStatus: QueueStatus;
  position: number | null;
  peopleAhead: number | null;
  estimatedWaitMinutes: number | null;
  nowServing: string | null;
  waitingCount: number;
  averageServiceMinutes: number;
  estimateBasis: EstimateBasis;
  queueVersion: number;
}

export interface Ticket {
  id: string;
  queueId: string;
  queueName: string;
  organizationId: string;
  organizationName: string;
  organizationSlug: string;
  timezone: string;
  tokenNumber: number;
  displayToken: string;
  status: TicketStatus;
  priority: TicketPriority;
  source: TicketSource;
  serviceDate: string;
  appointmentId: string | null;
  scheduledAt: string | null;
  createdAt: string;
  checkedInAt: string | null;
  calledAt: string | null;
  serviceStartedAt: string | null;
  completedAt: string | null;
  noShowAt: string | null;
  cancelledAt: string | null;
  expired: boolean;
  cancellable: boolean;
  live: LiveTicketInfo | null;
}

export interface BoardTicket {
  ticketId: string;
  tokenNumber: number;
  displayToken: string;
  holderName: string;
  source: TicketSource;
  status: TicketStatus;
  priority: TicketPriority;
  appointmentId: string | null;
  scheduledAt: string | null;
  createdAt: string;
  checkedInAt: string | null;
  calledAt: string | null;
  serviceStartedAt: string | null;
  position: number | null;
  estimatedWaitMinutes: number | null;
}

export interface BoardStats {
  waiting: number;
  expected: number;
  completed: number;
  noShow: number;
  cancelled: number;
  averageServiceMinutes: number;
  estimateBasis: EstimateBasis;
  estimateSamples: number;
}

export interface QueueBoard {
  queueId: string;
  queueName: string;
  organizationId: string;
  status: QueueStatus;
  serviceDate: string;
  timezone: string;
  version: number;
  atCounter: BoardTicket | null;
  waiting: BoardTicket[];
  expected: BoardTicket[];
  stats: BoardStats;
  orderingPolicy: string;
}

export interface Slot {
  startsAt: string;
  localTime: string;
  capacity: number;
  booked: number;
  available: number;
  bookable: boolean;
}

export interface Slots {
  queueId: string;
  date: string;
  timezone: string;
  bookingEnabled: boolean;
  slotDurationMinutes: number;
  firstBookableDate: string;
  lastBookableDate: string;
  slots: Slot[];
}

export interface Appointment {
  id: string;
  bookingReference: string;
  status: AppointmentStatus;
  queueId: string;
  queueName: string;
  organizationId: string;
  organizationName: string;
  organizationSlug: string;
  timezone: string;
  scheduledAt: string;
  serviceDate: string;
  ticketId: string | null;
  tokenNumber: number | null;
  displayToken: string | null;
  ticketStatus: TicketStatus | null;
  createdAt: string;
  cancelledAt: string | null;
  checkInOpensAt: string;
  canCancel: boolean;
  canCheckIn: boolean;
}

export interface AppNotification {
  id: string;
  type: NotificationType;
  title: string;
  message: string;
  ticketId: string | null;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationList {
  items: AppNotification[];
  unreadCount: number;
}

export interface MemberCounts {
  admins: number;
  staff: number;
  customers: number;
}

export interface OrganizationManagement {
  organization: Organization;
  yourRole: MembershipRole;
  members: MemberCounts;
}

export interface Member {
  membershipId: string;
  userId: string;
  email: string;
  fullName: string;
  role: MembershipRole;
  memberSince: string;
}

export interface QueueChangedEvent {
  queueId: string;
  version: number;
  reason: string;
  occurredAt: string;
}

export interface NotificationEvent {
  notificationId: string;
  type: NotificationType;
  ticketId: string | null;
}
