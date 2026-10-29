import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { BellRing, Hourglass, LogOut, PauseCircle } from 'lucide-react';
import { errorMessage } from '../api/client';
import { useCancelTicket, useTicket } from '../api/queries';
import type { Ticket } from '../api/types';
import { LiveIndicator, TicketStatusBadge } from '../components/StatusBadges';
import { Alert, Button, Card, ConfirmDialog, ErrorState, Spinner, Stat } from '../components/ui';
import { formatDateTime, formatLocalDate, formatTime, minutesLabel, sourceLabel } from '../lib/format';
import { useQueueEvents } from '../realtime/hooks';

function StatusMessage({ ticket }: { ticket: Ticket }) {
  if (ticket.expired) {
    return <Alert tone="info" title="This ticket has expired">Its service day has passed. Join the queue again if you still need the service.</Alert>;
  }
  switch (ticket.status) {
    case 'BOOKED':
      return <Alert tone="info" title="Token reserved">Check in from your appointment when you arrive; you join the line at check-in.</Alert>;
    case 'CALLED':
      return (
        <div className="flex items-center gap-3 rounded-xl bg-emerald-600 p-5 text-white shadow-lg" role="alert">
          <BellRing className="size-8 shrink-0 animate-pulse" aria-hidden />
          <div>
            <div className="text-xl font-semibold">It’s your turn!</div>
            <div className="text-emerald-50">Please go to {ticket.queueName} now.</div>
          </div>
        </div>
      );
    case 'SERVING':
      return <Alert tone="success" title="You are being served">Thank you for your patience.</Alert>;
    case 'COMPLETED':
      return <Alert tone="success" title="Service completed">Thanks for visiting {ticket.organizationName}.</Alert>;
    case 'NO_SHOW':
      return <Alert tone="warning" title="Marked as no-show">Your token was called but not answered. You can join the queue again.</Alert>;
    case 'CANCELLED':
      return <Alert tone="error" title="Ticket cancelled">This ticket is no longer in the queue.</Alert>;
    default:
      return null;
  }
}

export function TicketPage() {
  const { ticketId = '' } = useParams();
  const ticket = useTicket(ticketId);
  const cancel = useCancelTicket();
  const [confirming, setConfirming] = useState(false);
  const active = ticket.data && !ticket.data.expired && ['BOOKED', 'WAITING', 'CALLED', 'SERVING'].includes(ticket.data.status);
  const stream = useQueueEvents(ticket.data?.queueId, !!active);

  if (ticket.isPending) return <Spinner label="Loading your ticket" />;
  if (ticket.isError) return <ErrorState error={ticket.error} onRetry={() => ticket.refetch()} />;
  const t = ticket.data;
  const live = t.live;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link to={`/queues/${t.queueId}`} className="text-sm font-medium text-indigo-600 hover:underline">{t.organizationName} · {t.queueName}</Link>
          <h1 className="text-2xl font-semibold text-slate-900">Your ticket</h1>
        </div>
        <div className="flex items-center gap-2">
          <TicketStatusBadge status={t.status} expired={t.expired} />
          {active && <LiveIndicator status={stream} />}
        </div>
      </div>

      <StatusMessage ticket={t} />

      <Card className="p-8 text-center">
        <div className="text-sm font-medium uppercase tracking-widest text-slate-500">Token</div>
        <div className="mt-1 font-mono text-6xl font-bold text-indigo-600" data-testid="token">{t.displayToken}</div>
        <div className="mt-2 text-sm text-slate-500">{formatLocalDate(t.serviceDate)} · {sourceLabel[t.source]}</div>
        {live?.queueStatus === 'PAUSED' && (
          <div className="mt-4 inline-flex items-center gap-2 rounded-full bg-amber-50 px-3 py-1 text-sm text-amber-800 ring-1 ring-amber-200">
            <PauseCircle className="size-4" aria-hidden /> Queue paused — your place is kept, the estimate is on hold
          </div>
        )}
      </Card>

      {live && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4" aria-live="polite">
          <Stat label="Your position" value={t.status === 'WAITING' && live.position ? `#${live.position}` : t.status === 'BOOKED' ? 'Not in line yet' : '—'}
                hint={t.status === 'WAITING' && live.peopleAhead !== null ? `${live.peopleAhead} ahead of you` : undefined} />
          <Stat label="Estimated wait" value={t.status === 'WAITING' ? minutesLabel(live.estimatedWaitMinutes) : '—'}
                hint={live.estimateBasis === 'HISTORY' ? 'From recent service times' : 'From the typical service time'} />
          <Stat label="Now serving" value={live.nowServing ?? '—'} />
          <Stat label="Waiting in total" value={live.waitingCount} />
        </div>
      )}

      <Card>
        <h2 className="font-semibold text-slate-900">Details</h2>
        <dl className="mt-3 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          {t.scheduledAt && <><dt className="text-slate-500">Appointment</dt><dd>{formatDateTime(t.scheduledAt, t.timezone)}</dd></>}
          <dt className="text-slate-500">Issued</dt><dd>{formatTime(t.createdAt, t.timezone)}</dd>
          {t.checkedInAt && <><dt className="text-slate-500">Joined the line</dt><dd>{formatTime(t.checkedInAt, t.timezone)}</dd></>}
          {t.calledAt && <><dt className="text-slate-500">Called</dt><dd>{formatTime(t.calledAt, t.timezone)}</dd></>}
          {t.completedAt && <><dt className="text-slate-500">Completed</dt><dd>{formatTime(t.completedAt, t.timezone)}</dd></>}
          <dt className="text-slate-500">Priority</dt><dd>{t.priority === 'NORMAL' ? 'Normal' : t.priority === 'HIGH' ? 'High (set by staff)' : 'Low (set by staff)'}</dd>
        </dl>
        <p className="mt-4 flex items-start gap-2 text-xs text-slate-500">
          <Hourglass className="mt-0.5 size-3.5 shrink-0" aria-hidden />
          Positions follow the queue policy: staff-set priority first, then check-in time. Waiting times are estimates.
        </p>
      </Card>

      {cancel.isError && <Alert tone="error">{errorMessage(cancel.error)}</Alert>}
      <div className="flex flex-wrap gap-3">
        {t.cancellable && (
          <Button variant="secondary" icon={<LogOut className="size-4" aria-hidden />} onClick={() => setConfirming(true)}>
            {t.appointmentId ? 'Cancel appointment' : 'Leave the queue'}
          </Button>
        )}
        {t.appointmentId && <Link to={`/appointments/${t.appointmentId}`} className="rounded-lg px-4 py-2 text-sm font-medium text-indigo-600 hover:bg-indigo-50">View appointment</Link>}
        <Link to="/dashboard" className="rounded-lg px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100">Back to my queue</Link>
      </div>

      <ConfirmDialog open={confirming} title={t.appointmentId ? 'Cancel this appointment?' : 'Leave the queue?'}
                     confirmLabel={t.appointmentId ? 'Cancel appointment' : 'Leave queue'} loading={cancel.isPending}
                     onCancel={() => setConfirming(false)}
                     onConfirm={() => cancel.mutate({ id: t.id, queueId: t.queueId }, { onSettled: () => setConfirming(false) })}>
        Token {t.displayToken} will be cancelled and cannot re-enter the queue. {t.appointmentId && 'The slot is released for someone else.'}
      </ConfirmDialog>

    </div>
  );
}
