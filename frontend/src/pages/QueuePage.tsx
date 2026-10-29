import { Link, useNavigate, useParams } from 'react-router';
import { CalendarPlus, Clock, LogIn, PauseCircle, Ticket as TicketIcon } from 'lucide-react';
import { errorMessage, isApiError } from '../api/client';
import { useJoinQueue, useMe, useMyTickets, useQueue, useQueueLive } from '../api/queries';
import { LiveIndicator, QueueStatusBadge } from '../components/StatusBadges';
import { QueueLivePanel } from '../components/QueueLivePanel';
import { Alert, Button, Card, ErrorState, PageHeader, Spinner } from '../components/ui';
import { formatLocalTime } from '../lib/format';
import { useQueueEvents } from '../realtime/hooks';

export function QueuePage() {
  const { queueId = '' } = useParams();
  const me = useMe();
  const signedIn = !!me.data;
  const queue = useQueue(queueId);
  const live = useQueueLive(queueId);
  const myTickets = useMyTickets(signedIn);
  const join = useJoinQueue(queueId);
  const navigate = useNavigate();
  const stream = useQueueEvents(queueId, signedIn); // the event stream requires a session

  if (queue.isPending) return <Spinner />;
  if (queue.isError) return <ErrorState error={queue.error} onRetry={() => queue.refetch()} />;
  const q = queue.data;

  const activeTicket = myTickets.data?.find((t) => t.queueId === queueId && !t.expired
    && ['BOOKED', 'WAITING', 'CALLED', 'SERVING'].includes(t.status) && t.live !== null);

  const joinQueue = () => join.mutate(undefined, { onSuccess: (ticket) => navigate(`/tickets/${ticket.id}`) });

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow={<Link to={`/organizations/${q.organizationSlug}`} className="hover:underline">{q.organizationName}</Link>}
        title={<span className="flex flex-wrap items-center gap-3">{q.name} <QueueStatusBadge status={q.status} /></span>}
        subtitle={q.description}
        actions={<LiveIndicator status={stream} />}
      />

      <Card>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold text-slate-900">Right now</h2>
          <span className="inline-flex items-center gap-1 text-sm text-slate-500">
            <Clock className="size-4" aria-hidden /> Open {formatLocalTime(q.openingTime)}–{formatLocalTime(q.closingTime)} ({q.timezone})
          </span>
        </div>
        {live.isPending && <Spinner label="Loading live status" />}
        {live.isError && <ErrorState error={live.error} onRetry={() => live.refetch()} />}
        {live.data && <QueueLivePanel live={live.data} />}
        {!signedIn && <p className="mt-3 text-xs text-slate-500">Log in to see these numbers update live.</p>}
      </Card>

      {q.status === 'PAUSED' && (
        <Alert tone="warning" title="This queue is paused">
          Staff have paused calling for a moment. You can still join; your place is kept.
        </Alert>
      )}

      {activeTicket ? (
        <Card className="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="font-medium text-slate-900">You already have token {activeTicket.displayToken} in this queue.</div>
            <div className="text-sm text-slate-600">Follow your position live on the ticket page.</div>
          </div>
          <Link to={`/tickets/${activeTicket.id}`} className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">
            View my ticket
          </Link>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          <Card>
            <TicketIcon className="size-6 text-indigo-600" aria-hidden />
            <h2 className="mt-2 font-semibold text-slate-900">Join the queue now</h2>
            <p className="mt-1 text-sm text-slate-600">Take a token for today and join the line immediately (walk-in).</p>
            {join.isError && (
              <div className="mt-3">
                <Alert tone="error">
                  {errorMessage(join.error)}
                  {isApiError(join.error, 'ALREADY_IN_QUEUE') && typeof join.error.problem.ticketId === 'string' && (
                    <> <Link className="font-medium underline" to={`/tickets/${join.error.problem.ticketId}`}>Open it</Link></>
                  )}
                </Alert>
              </div>
            )}
            <div className="mt-4">
              {signedIn ? (
                <Button onClick={joinQueue} loading={join.isPending} disabled={q.status === 'CLOSED'}
                        icon={q.status === 'PAUSED' ? <PauseCircle className="size-4" aria-hidden /> : <TicketIcon className="size-4" aria-hidden />}>
                  {q.status === 'CLOSED' ? 'Queue closed' : 'Get a token'}
                </Button>
              ) : (
                <Link to={`/login?next=/queues/${queueId}`} className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">
                  <LogIn className="size-4" aria-hidden /> Log in to join
                </Link>
              )}
            </div>
          </Card>
          <Card>
            <CalendarPlus className="size-6 text-indigo-600" aria-hidden />
            <h2 className="mt-2 font-semibold text-slate-900">Book an appointment</h2>
            <p className="mt-1 text-sm text-slate-600">
              Reserve a {q.slotDurationMinutes}-minute slot. Your token is reserved now; you join the line when you check in.
            </p>
            <div className="mt-4">
              {q.bookingEnabled ? (
                <Link to={signedIn ? `/queues/${queueId}/book` : `/login?next=/queues/${queueId}/book`}
                      className="inline-flex items-center gap-2 rounded-lg bg-white px-4 py-2 text-sm font-medium text-slate-700 shadow-sm ring-1 ring-slate-300 hover:bg-slate-50">
                  <CalendarPlus className="size-4" aria-hidden /> Choose a slot
                </Link>
              ) : (
                <p className="text-sm text-slate-500">This queue does not take appointments.</p>
              )}
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
