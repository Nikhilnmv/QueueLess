import { Link, useNavigate, useParams } from 'react-router';
import { errorMessage, isApiError } from '../api/client';
import { useJoinQueue, useMe, useMyTickets, useQueue, useQueueLive } from '../api/queries';
import { LiveIndicator, QueueStatusBadge } from '../components/StatusBadges';
import { Button, Details, ErrorNotice, Loading, Notice, PageTitle, Panel } from '../components/ui';
import { formatLocalTime, minutesLabel } from '../lib/format';
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
  const stream = useQueueEvents(queueId, signedIn); // the event stream needs a session

  if (queue.isPending) return <Loading />;
  if (queue.isError) return <ErrorNotice error={queue.error} onRetry={() => queue.refetch()} />;
  const q = queue.data;

  const activeTicket = myTickets.data?.find((t) => t.queueId === queueId && !t.expired
    && ['BOOKED', 'WAITING', 'CALLED', 'SERVING'].includes(t.status) && t.live !== null);

  const joinQueue = () => join.mutate(undefined, { onSuccess: (ticket) => navigate(`/tickets/${ticket.id}`) });

  return (
    <div className="space-y-6">
      <PageTitle
        back={<Link to={`/organizations/${q.organizationSlug}`} className="text-pine-700 hover:underline">{q.organizationName}</Link>}
        title={q.name}
        description={q.description}
        actions={<><QueueStatusBadge status={q.status} /><LiveIndicator status={stream} /></>}
      />

      {q.status === 'PAUSED' && (
        <Notice tone="warning" title="The queue is paused">
          Staff have stopped calling for a short while. You can still join and keep your place.
        </Notice>
      )}

      <div className="grid gap-6 md:grid-cols-[3fr_2fr]">
        <Panel title="Right now">
          {live.isPending && <Loading />}
          {live.isError && <ErrorNotice error={live.error} onRetry={() => live.refetch()} />}
          {live.data && (
            <Details rows={[
              ['Now serving', <span className="font-mono">{live.data.nowServing?.displayToken ?? 'Nobody at the counter'}</span>],
              ['Waiting', `${live.data.waitingCount} ${live.data.waitingCount === 1 ? 'person' : 'people'}`],
              ['Wait if you join now', minutesLabel(live.data.estimatedWaitMinutesForNewTicket)],
              ['Average service time', `${live.data.averageServiceMinutes} min ${live.data.estimateBasis === 'HISTORY'
                ? `(last ${live.data.estimateSamples} visits)` : '(typical time, not enough history yet)'}`],
              ['Opening hours', `${formatLocalTime(q.openingTime)} to ${formatLocalTime(q.closingTime)} (${q.timezone})`],
            ]} />
          )}
          {!signedIn && <p className="mt-4 text-xs text-stone-500">Log in to have these numbers update by themselves.</p>}
        </Panel>

        <div className="space-y-4">
          {activeTicket ? (
            <Panel title="Your ticket">
              <p className="text-sm text-stone-700">You already have token <span className="font-mono font-medium">{activeTicket.displayToken}</span> in this queue.</p>
              <Link to={`/tickets/${activeTicket.id}`} className="mt-3 inline-block rounded bg-pine-700 px-3.5 py-2 text-sm font-medium text-white hover:bg-pine-800">
                Open my ticket
              </Link>
            </Panel>
          ) : (
            <>
              <Panel title="Join today's queue">
                <p className="text-sm text-stone-600">Take a token now and wait for your turn.</p>
                {join.isError && (
                  <div className="mt-3">
                    <Notice tone="error">
                      {errorMessage(join.error)}
                      {isApiError(join.error, 'ALREADY_IN_QUEUE') && typeof join.error.problem.ticketId === 'string' && (
                        <> <Link className="underline" to={`/tickets/${join.error.problem.ticketId}`}>Open it</Link></>
                      )}
                    </Notice>
                  </div>
                )}
                <div className="mt-3">
                  {signedIn ? (
                    <Button onClick={joinQueue} loading={join.isPending} disabled={q.status === 'CLOSED'}>
                      {q.status === 'CLOSED' ? 'Queue closed' : 'Get a token'}
                    </Button>
                  ) : (
                    <Link to={`/login?next=/queues/${queueId}`} className="text-pine-700 underline underline-offset-2">Log in to take a token</Link>
                  )}
                </div>
              </Panel>
              <Panel title="Book an appointment">
                {q.bookingEnabled ? (
                  <>
                    <p className="text-sm text-stone-600">
                      Reserve a {q.slotDurationMinutes}-minute slot. You get your token now and join the line when you check in.
                    </p>
                    <Link to={signedIn ? `/queues/${queueId}/book` : `/login?next=/queues/${queueId}/book`}
                          className="mt-3 inline-block rounded border border-stone-300 bg-white px-3.5 py-2 text-sm font-medium text-stone-800 hover:bg-stone-50">
                      Choose a time
                    </Link>
                  </>
                ) : (
                  <p className="text-sm text-stone-600">This counter does not take appointments.</p>
                )}
              </Panel>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
