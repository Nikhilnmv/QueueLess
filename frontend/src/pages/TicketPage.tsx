import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { errorMessage } from '../api/client';
import { useCancelTicket, useTicket } from '../api/queries';
import type { Ticket } from '../api/types';
import { LiveIndicator, TicketStatusBadge } from '../components/StatusBadges';
import { Button, ConfirmDialog, Details, ErrorNotice, Loading, Notice, PageTitle, Panel } from '../components/ui';
import { formatDateTime, formatLocalDate, formatTime, minutesLabel, priorityLabel, sourceLabel } from '../lib/format';
import { useQueueEvents } from '../realtime/hooks';

function StatusMessage({ ticket }: { ticket: Ticket }) {
  if (ticket.expired) {
    return <Notice title="This ticket has expired">Its day has passed. Take a new token if you still need the service.</Notice>;
  }
  switch (ticket.status) {
    case 'BOOKED':
      return <Notice title="Token reserved">Check in from your appointment when you arrive. You join the line at check-in.</Notice>;
    case 'CALLED':
      return (
        <div className="rounded-md bg-pine-700 px-5 py-4 text-white" role="alert">
          <p className="text-xl font-semibold">It’s your turn!</p>
          <p className="text-pine-100">Please go to {ticket.queueName} now.</p>
        </div>
      );
    case 'SERVING':
      return <Notice tone="success" title="You are being served" />;
    case 'COMPLETED':
      return <Notice tone="success" title="Service completed">Thank you for visiting {ticket.organizationName}.</Notice>;
    case 'NO_SHOW':
      return <Notice tone="warning" title="Marked as no-show">Your token was called but nobody answered. You can take a new token.</Notice>;
    case 'CANCELLED':
      return <Notice tone="error" title="Ticket cancelled">This ticket is no longer in the queue.</Notice>;
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

  if (ticket.isPending) return <Loading label="Loading your ticket" />;
  if (ticket.isError) return <ErrorNotice error={ticket.error} onRetry={() => ticket.refetch()} />;
  const t = ticket.data;
  const live = t.live;

  return (
    <div className="mx-auto max-w-3xl space-y-5">
      <PageTitle
        back={<Link to="/dashboard" className="text-pine-700 hover:underline">My visits</Link>}
        title={`${t.queueName}, ${t.organizationName}`}
        actions={<><TicketStatusBadge status={t.status} expired={t.expired} />{active && <LiveIndicator status={stream} />}</>}
      />

      <StatusMessage ticket={t} />

      <div className="grid gap-5 sm:grid-cols-[14rem_1fr]">
        <div className="rounded-md border border-stone-200 bg-white px-5 py-6 text-center">
          <p className="text-xs font-medium uppercase tracking-wider text-stone-500">Your token</p>
          <p className="mt-1 font-mono text-5xl font-medium text-pine-700" data-testid="token">{t.displayToken}</p>
          <p className="mt-2 text-sm text-stone-500">{formatLocalDate(t.serviceDate, 'short')}</p>
        </div>

        <Panel title="Your place in line">
          {live && t.status === 'WAITING' && live.position !== null ? (
            <div aria-live="polite">
              <p className="text-2xl font-semibold text-stone-900">
                Number <span data-testid="position">{live.position}</span> in line
              </p>
              <p className="mt-1 text-stone-600">
                {live.peopleAhead === 0 ? 'You are next.' : `${live.peopleAhead} ${live.peopleAhead === 1 ? 'person' : 'people'} ahead of you.`}
                {' '}Estimated wait {minutesLabel(live.estimatedWaitMinutes).replace('~', 'about ')}.
              </p>
              <p className="mt-3 text-sm text-stone-500">
                Now serving {live.nowServing ? <span className="font-mono">{live.nowServing}</span> : 'nobody'} · {live.waitingCount} waiting in total
                {live.queueStatus === 'PAUSED' && ' · the queue is paused, so the estimate is on hold'}
              </p>
            </div>
          ) : (
            <p className="text-sm text-stone-600">
              {t.status === 'BOOKED' ? 'You will get a place in line when you check in.'
                : t.status === 'CALLED' || t.status === 'SERVING' ? 'You are at the counter.'
                  : 'This ticket is no longer in the line.'}
            </p>
          )}
        </Panel>
      </div>

      <Panel title="Details">
        <Details rows={[
          ...(t.scheduledAt ? [['Appointment', formatDateTime(t.scheduledAt, t.timezone)] as [string, string]] : []),
          ['How you joined', sourceLabel[t.source]],
          ['Issued at', formatTime(t.createdAt, t.timezone)],
          ...(t.checkedInAt ? [['Joined the line', formatTime(t.checkedInAt, t.timezone)] as [string, string]] : []),
          ...(t.calledAt ? [['Called at', formatTime(t.calledAt, t.timezone)] as [string, string]] : []),
          ...(t.completedAt ? [['Completed at', formatTime(t.completedAt, t.timezone)] as [string, string]] : []),
          ['Priority', t.priority === 'NORMAL' ? 'Normal' : `${priorityLabel[t.priority]} (set by staff)`],
        ]} />
        <p className="mt-4 text-xs text-stone-500">
          The line is ordered by priority set by staff, then by the time you joined. Waiting times are estimates.
        </p>
      </Panel>

      {cancel.isError && <Notice tone="error">{errorMessage(cancel.error)}</Notice>}
      <div className="flex flex-wrap items-center gap-4">
        {t.cancellable && (
          <Button variant="danger" onClick={() => setConfirming(true)}>
            {t.appointmentId ? 'Cancel appointment' : 'Leave the queue'}
          </Button>
        )}
        {t.appointmentId && <Link to={`/appointments/${t.appointmentId}`} className="text-sm text-pine-700 underline underline-offset-2">View the appointment</Link>}
      </div>

      <ConfirmDialog open={confirming} title={t.appointmentId ? 'Cancel this appointment?' : 'Leave the queue?'}
                     confirmLabel={t.appointmentId ? 'Cancel appointment' : 'Leave queue'} loading={cancel.isPending}
                     onCancel={() => setConfirming(false)}
                     onConfirm={() => cancel.mutate({ id: t.id, queueId: t.queueId }, { onSettled: () => setConfirming(false) })}>
        Token {t.displayToken} will be cancelled and cannot go back into the queue.{t.appointmentId && ' The slot becomes free for someone else.'}
      </ConfirmDialog>
    </div>
  );
}
