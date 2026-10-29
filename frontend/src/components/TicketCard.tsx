import { Link } from 'react-router';
import { ChevronRight } from 'lucide-react';
import type { Ticket } from '../api/types';
import { formatLocalDate, minutesLabel } from '../lib/format';
import { TicketStatusBadge } from './StatusBadges';
import { Card } from './ui';

export function TicketCard({ ticket }: { ticket: Ticket }) {
  const live = ticket.live;
  return (
    <Link to={`/tickets/${ticket.id}`} className="group">
      <Card className="flex items-center gap-4 transition-shadow group-hover:shadow-md">
        <div className="flex size-16 shrink-0 flex-col items-center justify-center rounded-xl bg-indigo-600 text-white">
          <span className="text-[10px] uppercase tracking-wide text-indigo-200">Token</span>
          <span className="font-mono text-lg font-bold">{ticket.displayToken}</span>
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium text-slate-900">{ticket.queueName}</span>
            <TicketStatusBadge status={ticket.status} expired={ticket.expired} />
          </div>
          <div className="text-sm text-slate-600">{ticket.organizationName} · {formatLocalDate(ticket.serviceDate, 'short')}</div>
          {live && ticket.status === 'WAITING' && live.position !== null && (
            <div className="mt-1 text-sm text-slate-700">
              Position <strong>#{live.position}</strong> · estimated wait <strong>{minutesLabel(live.estimatedWaitMinutes)}</strong>
            </div>
          )}
          {(ticket.status === 'CALLED' || ticket.status === 'SERVING') && !ticket.expired && (
            <div className="mt-1 text-sm font-medium text-emerald-700">It’s your turn — please go to the counter.</div>
          )}
        </div>
        <ChevronRight className="size-5 shrink-0 text-slate-400" aria-hidden />
      </Card>
    </Link>
  );
}
