import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { errorMessage } from '../../api/client';
import { useBoard, useStaffActions } from '../../api/queries';
import type { BoardTicket, QueueStatus, TicketPriority } from '../../api/types';
import { LiveIndicator, PriorityBadge, QueueStatusBadge, TicketStatusBadge } from '../../components/StatusBadges';
import { Button, ConfirmDialog, Details, Empty, ErrorNotice, Field, Input, Loading, Notice, PageTitle, Panel, Select, cx, table, td,
  th } from '../../components/ui';
import { formatLocalDate, formatTime, minutesLabel, priorityLabel, sourceLabel } from '../../lib/format';
import { useQueueEvents } from '../../realtime/hooks';

function minutesSince(iso: string | null): string {
  if (!iso) return '';
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60_000));
  return minutes === 0 ? 'just now' : `${minutes} min ago`;
}

const statusChoices: { status: QueueStatus; label: string }[] = [
  { status: 'OPEN', label: 'Open' },
  { status: 'PAUSED', label: 'Pause' },
  { status: 'CLOSED', label: 'Close' },
];

export function StaffQueuePage() {
  const { queueId = '' } = useParams();
  const board = useBoard(queueId);
  const actions = useStaffActions(queueId);
  const stream = useQueueEvents(queueId);
  const [guestName, setGuestName] = useState('');
  const [guestPriority, setGuestPriority] = useState<TicketPriority>('NORMAL');
  const [cancelling, setCancelling] = useState<BoardTicket | null>(null);

  if (board.isPending) return <Loading label="Loading the board" />;
  if (board.isError) return <ErrorNotice error={board.error} onRetry={() => board.refetch()} />;
  const b = board.data;
  const counter = b.atCounter;

  const errors = Object.values(actions).filter((m) => m.isError).map((m) => errorMessage(m.error));
  const busy = Object.values(actions).some((m) => m.isPending);
  const resetErrors = () => Object.values(actions).forEach((m) => m.reset());

  const callNextBlocked = counter
    ? `Finish ${counter.displayToken} first.`
    : b.status !== 'OPEN' ? 'Open the queue to call someone.' : b.waiting.length === 0 ? 'Nobody is waiting.' : null;

  const registerGuest = (event: FormEvent) => {
    event.preventDefault();
    if (!guestName.trim()) return;
    resetErrors();
    actions.guest.mutate({ guestName: guestName.trim(), priority: guestPriority },
      { onSuccess: () => { setGuestName(''); setGuestPriority('NORMAL'); } });
  };

  const setStatus = (status: QueueStatus) => { resetErrors(); actions.status.mutate(status); };

  return (
    <div className="space-y-6">
      <PageTitle
        back={<Link to="/staff" className="text-pine-700 hover:underline">Staff</Link>}
        title={b.queueName}
        description={`${formatLocalDate(b.serviceDate)} · ${b.timezone}`}
        actions={
          <>
            <LiveIndicator status={stream} />
            <QueueStatusBadge status={b.status} />
            <div role="group" aria-label="Change queue status" className="flex overflow-hidden rounded border border-stone-300">
              {statusChoices.map(({ status, label }) => (
                <button key={status} type="button" disabled={b.status === status || busy} onClick={() => setStatus(status)}
                        className="border-l border-stone-300 bg-white px-3 py-1.5 text-sm text-stone-700 first:border-l-0 hover:bg-stone-50
                                   disabled:bg-stone-50 disabled:text-stone-400">
                  {label}
                </button>
              ))}
            </div>
          </>
        }
      />

      {errors.length > 0 && <Notice tone="error">{errors[0]}</Notice>}

      <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
        <Panel title="At the counter">
          {counter ? (
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-4">
                <span className="font-mono text-4xl font-medium text-pine-700">{counter.displayToken}</span>
                <div>
                  <p className="font-medium text-stone-900">{counter.holderName}</p>
                  <p className="mt-0.5 flex flex-wrap items-center gap-2 text-sm text-stone-600">
                    <TicketStatusBadge status={counter.status} />
                    {counter.status === 'CALLED'
                      ? <span>{minutesSince(counter.calledAt)}</span>
                      : <span>since {formatTime(counter.serviceStartedAt, b.timezone)}</span>}
                  </p>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
                {counter.status === 'CALLED' && (
                  <>
                    <Button loading={actions.start.isPending} disabled={busy}
                            onClick={() => { resetErrors(); actions.start.mutate(counter.ticketId); }}>Start service</Button>
                    <Button variant="secondary" loading={actions.noShow.isPending} disabled={busy}
                            onClick={() => { resetErrors(); actions.noShow.mutate(counter.ticketId); }}>No-show</Button>
                  </>
                )}
                {counter.status === 'SERVING' && (
                  <Button loading={actions.complete.isPending} disabled={busy}
                          onClick={() => { resetErrors(); actions.complete.mutate(counter.ticketId); }}>Complete</Button>
                )}
              </div>
            </div>
          ) : (
            <p className="text-stone-600">The counter is free.</p>
          )}
        </Panel>

        <Panel title="Next">
          <Button size="lg" className="w-full" loading={actions.callNext.isPending} disabled={!!callNextBlocked || busy}
                  onClick={() => { resetErrors(); actions.callNext.mutate(undefined); }}>
            Call next
          </Button>
          <p className="mt-2 text-center text-sm text-stone-500">
            {callNextBlocked ?? <>Next up: <span className="font-mono">{b.waiting[0]?.displayToken}</span>, {b.waiting[0]?.holderName}</>}
          </p>
        </Panel>
      </div>

      <div className="grid items-start gap-6 lg:grid-cols-[2fr_1fr]">
        <div className="min-w-0 space-y-6">
          <Panel title={`Waiting (${b.waiting.length})`} actions={<span className="text-xs text-stone-500">{b.orderingPolicy}</span>}>
            {b.waiting.length === 0 ? (
              <Empty>Nobody is waiting. Walk-ins and checked-in appointments appear here as they arrive.</Empty>
            ) : (
              <div className="-m-4 overflow-x-auto">
                <table className={table}>
                  <thead>
                    <tr>
                      <th className={th}>#</th><th className={th}>Token</th><th className={th}>Name</th><th className={th}>Joined by</th>
                      <th className={th}>Priority</th><th className={th}>In line since</th><th className={th}>Wait</th>
                      <th className={th}><span className="sr-only">Actions</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {b.waiting.map((t) => (
                      <tr key={t.ticketId} className={cx(t.priority === 'HIGH' && 'bg-amber-50/60')}>
                        <td className={cx(td, 'tabular text-stone-500')}>{t.position}</td>
                        <td className={cx(td, 'font-mono font-medium')}>{t.displayToken}</td>
                        <td className={td}>{t.holderName}</td>
                        <td className={cx(td, 'text-stone-600')}>{sourceLabel[t.source]}</td>
                        <td className={td}>
                          <label className="sr-only" htmlFor={`priority-${t.ticketId}`}>Priority of {t.displayToken}</label>
                          <Select id={`priority-${t.ticketId}`} value={t.priority} disabled={busy} className="w-28 py-1!"
                                  onChange={(e) => {
                                    resetErrors();
                                    actions.priority.mutate({ ticketId: t.ticketId, priority: e.target.value as TicketPriority });
                                  }}>
                            {(['HIGH', 'NORMAL', 'LOW'] as const).map((p) => <option key={p} value={p}>{priorityLabel[p]}</option>)}
                          </Select>
                        </td>
                        <td className={cx(td, 'tabular text-stone-600')}>{formatTime(t.checkedInAt, b.timezone)}</td>
                        <td className={cx(td, 'tabular whitespace-nowrap text-stone-600')}>{minutesLabel(t.estimatedWaitMinutes)}</td>
                        <td className={cx(td, 'text-right')}>
                          <button type="button" disabled={busy} onClick={() => setCancelling(t)}
                                  className="text-sm text-red-700 hover:underline disabled:text-stone-400">Cancel</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <Panel title={`Expected today (${b.expected.length})`}>
            {b.expected.length === 0 ? (
              <p className="text-sm text-stone-500">No booked visitors are still expected today.</p>
            ) : (
              <div className="-m-4 overflow-x-auto">
                <table className={table}>
                  <thead>
                    <tr>
                      <th className={th}>Time</th><th className={th}>Token</th><th className={th}>Name</th><th className={th}>Priority</th>
                      <th className={th}><span className="sr-only">Actions</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {b.expected.map((t) => (
                      <tr key={t.ticketId}>
                        <td className={cx(td, 'tabular')}>{formatTime(t.scheduledAt, b.timezone)}</td>
                        <td className={cx(td, 'font-mono font-medium')}>{t.displayToken}</td>
                        <td className={td}>{t.holderName}</td>
                        <td className={td}>{t.priority === 'NORMAL' ? <span className="text-stone-500">Normal</span> : <PriorityBadge priority={t.priority} />}</td>
                        <td className={cx(td, 'whitespace-nowrap text-right')}>
                          <Button size="sm" variant="secondary" disabled={busy || !t.appointmentId || b.status === 'CLOSED'}
                                  onClick={() => { resetErrors(); if (t.appointmentId) actions.checkIn.mutate(t.appointmentId); }}>
                            Check in
                          </Button>
                          <button type="button" disabled={busy} onClick={() => setCancelling(t)}
                                  className="ml-3 text-sm text-red-700 hover:underline disabled:text-stone-400">Cancel</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </div>

        <div className="space-y-6">
          <Panel title="Register a walk-in">
            <form onSubmit={registerGuest} className="space-y-3">
              <p className="text-sm text-stone-600">For visitors without an account. They get the next token for today.</p>
              <Field label="Visitor name" htmlFor="guestName">
                <Input id="guestName" value={guestName} maxLength={120} onChange={(e) => setGuestName(e.target.value)} />
              </Field>
              <Field label="Priority" htmlFor="guestPriority">
                <Select id="guestPriority" value={guestPriority} onChange={(e) => setGuestPriority(e.target.value as TicketPriority)}>
                  {(['NORMAL', 'HIGH', 'LOW'] as const).map((p) => <option key={p} value={p}>{priorityLabel[p]}</option>)}
                </Select>
              </Field>
              <Button type="submit" loading={actions.guest.isPending} disabled={!guestName.trim() || busy || b.status === 'CLOSED'}>
                Add to queue
              </Button>
            </form>
          </Panel>

          <Panel title="Today">
            <Details rows={[
              ['Waiting', b.stats.waiting],
              ['Expected', `${b.stats.expected} booked, not checked in`],
              ['Completed', b.stats.completed],
              ['No-shows', b.stats.noShow],
              ['Cancelled', b.stats.cancelled],
              ['Average service', `${b.stats.averageServiceMinutes} min (${b.stats.estimateBasis === 'HISTORY'
                ? `last ${b.stats.estimateSamples} visits` : 'typical time, little history yet'})`],
            ]} />
          </Panel>
        </div>
      </div>

      <ConfirmDialog open={!!cancelling} title={`Cancel ${cancelling?.displayToken ?? ''}?`} confirmLabel="Cancel ticket"
                     loading={actions.cancel.isPending} onCancel={() => setCancelling(null)}
                     onConfirm={() => cancelling && actions.cancel.mutate(cancelling.ticketId, { onSettled: () => setCancelling(null) })}>
        {cancelling?.holderName} is removed from today’s queue{cancelling?.appointmentId ? ' and the appointment is cancelled' : ''}.
        They get a notification.
      </ConfirmDialog>
    </div>
  );
}
