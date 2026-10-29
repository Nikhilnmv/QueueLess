import { Wifi, WifiOff } from 'lucide-react';
import type { AppointmentStatus, QueueStatus, TicketPriority, TicketStatus } from '../api/types';
import { appointmentStatusLabel, priorityLabel, queueStatusLabel, ticketStatusLabel } from '../lib/format';
import type { StreamStatus } from '../realtime/useEventStream';
import { Badge, cx } from './ui';

export function QueueStatusBadge({ status }: { status: QueueStatus }) {
  const tone = status === 'OPEN' ? 'green' : status === 'PAUSED' ? 'amber' : 'neutral';
  return <Badge tone={tone}>{queueStatusLabel[status]}</Badge>;
}

export function TicketStatusBadge({ status, expired = false }: { status: TicketStatus; expired?: boolean }) {
  if (expired) return <Badge tone="neutral">Expired</Badge>;
  const tone = {
    BOOKED: 'blue', WAITING: 'indigo', CALLED: 'green', SERVING: 'green',
    COMPLETED: 'neutral', NO_SHOW: 'amber', CANCELLED: 'red',
  } as const;
  return <Badge tone={tone[status]}>{ticketStatusLabel[status]}</Badge>;
}

export function AppointmentStatusBadge({ status }: { status: AppointmentStatus }) {
  const tone = {
    BOOKED: 'blue', CHECKED_IN: 'indigo', COMPLETED: 'neutral', NO_SHOW: 'amber', CANCELLED: 'red',
  } as const;
  return <Badge tone={tone[status]}>{appointmentStatusLabel[status]}</Badge>;
}

export function PriorityBadge({ priority }: { priority: TicketPriority }) {
  if (priority === 'NORMAL') return <Badge>Normal</Badge>;
  return <Badge tone={priority === 'HIGH' ? 'red' : 'neutral'}>{priorityLabel[priority]}</Badge>;
}

/** Shows whether live updates are flowing; when not, the page still shows the last snapshot. */
export function LiveIndicator({ status }: { status: StreamStatus }) {
  if (status === 'idle') return null;
  const live = status === 'live';
  return (
    <span
      className={cx('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ring-1 ring-inset',
        live ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-amber-50 text-amber-800 ring-amber-200')}
      title={live ? 'Receiving live updates' : 'Reconnecting; showing the latest data we have'}
    >
      {live ? <Wifi className="size-3.5" aria-hidden /> : <WifiOff className="size-3.5" aria-hidden />}
      {live ? 'Live' : status === 'connecting' ? 'Connecting…' : 'Reconnecting…'}
    </span>
  );
}
