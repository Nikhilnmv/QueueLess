import type { AppointmentStatus, QueueStatus, TicketPriority, TicketStatus } from '../api/types';
import { appointmentStatusLabel, priorityLabel, queueStatusLabel, ticketStatusLabel } from '../lib/format';
import type { StreamStatus } from '../realtime/useEventStream';
import { cx } from './ui';

type Colour = 'green' | 'amber' | 'grey' | 'red' | 'blue';
const dots: Record<Colour, string> = {
  green: 'bg-pine-500',
  amber: 'bg-amber-500',
  grey: 'bg-stone-400',
  red: 'bg-red-500',
  blue: 'bg-sky-600',
};

/** A label with a small coloured dot in front of it. */
function Status({ colour, children }: { colour: Colour; children: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-sm text-stone-700">
      <span className={cx('size-2 rounded-full', dots[colour])} aria-hidden />
      {children}
    </span>
  );
}

export function QueueStatusBadge({ status }: { status: QueueStatus }) {
  return <Status colour={status === 'OPEN' ? 'green' : status === 'PAUSED' ? 'amber' : 'grey'}>{queueStatusLabel[status]}</Status>;
}

export function TicketStatusBadge({ status, expired = false }: { status: TicketStatus; expired?: boolean }) {
  if (expired) return <Status colour="grey">Expired</Status>;
  const colour: Record<TicketStatus, Colour> = {
    BOOKED: 'blue', WAITING: 'amber', CALLED: 'green', SERVING: 'green',
    COMPLETED: 'grey', NO_SHOW: 'red', CANCELLED: 'grey',
  };
  return <Status colour={colour[status]}>{ticketStatusLabel[status]}</Status>;
}

export function AppointmentStatusBadge({ status }: { status: AppointmentStatus }) {
  const colour: Record<AppointmentStatus, Colour> = {
    BOOKED: 'blue', CHECKED_IN: 'amber', COMPLETED: 'grey', NO_SHOW: 'red', CANCELLED: 'grey',
  };
  return <Status colour={colour[status]}>{appointmentStatusLabel[status]}</Status>;
}

export function PriorityBadge({ priority }: { priority: TicketPriority }) {
  return (
    <span className={cx('text-sm', priority === 'HIGH' ? 'font-semibold text-red-700' : 'text-stone-600')}>
      {priorityLabel[priority]}
    </span>
  );
}

/** Says whether live updates are arriving; the page always shows the last data it fetched. */
export function LiveIndicator({ status }: { status: StreamStatus }) {
  if (status === 'idle') return null;
  const live = status === 'live';
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-stone-500"
          title={live ? 'This page updates by itself' : 'Trying to reconnect; showing the latest data'}>
      <span className={cx('size-2 rounded-full', live ? 'bg-pine-500' : 'bg-amber-500')} aria-hidden />
      {live ? 'Live' : status === 'connecting' ? 'Connecting…' : 'Reconnecting…'}
    </span>
  );
}
