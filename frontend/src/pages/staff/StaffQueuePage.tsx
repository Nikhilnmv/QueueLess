import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { CheckCircle2, DoorClosed, DoorOpen, Megaphone, PauseCircle, PlayCircle, UserPlus, UserX, XCircle } from 'lucide-react';
import { errorMessage } from '../../api/client';
import { useBoard, useStaffActions } from '../../api/queries';
import type { BoardTicket, QueueStatus, TicketPriority } from '../../api/types';
import { LiveIndicator, PriorityBadge, QueueStatusBadge, TicketStatusBadge } from '../../components/StatusBadges';
import { Alert, Button, Card, ConfirmDialog, EmptyState, ErrorState, Input, PageHeader, Select, Spinner, Stat, cx } from '../../components/ui';
import { formatTime, minutesLabel, priorityLabel, sourceLabel } from '../../lib/format';
import { useQueueEvents } from '../../realtime/hooks';

function minutesSince(iso: string | null): string {
  if (!iso) return '';
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60_000));
  return minutes === 0 ? 'just now' : `${minutes} min ago`;
}

export function StaffQueuePage() {
  const { queueId = '' } = useParams();
  const board = useBoard(queueId);
  const actions = useStaffActions(queueId);
  const stream = useQueueEvents(queueId);
  const [guestName, setGuestName] = useState('');
  const [guestPriority, setGuestPriority] = useState<TicketPriority>('NORMAL');
  const [cancelling, setCancelling] = useState<BoardTicket | null>(null);

  if (board.isPending) return <Spinner label="Loading live board" />;
  if (board.isError) return <ErrorState error={board.error} onRetry={() => board.refetch()} />;
  const b = board.data;
  const counter = b.atCounter;

  const errors = Object.values(actions).filter((m) => m.isError).map((m) => errorMessage(m.error));
  const busy = Object.values(actions).some((m) => m.isPending);
  const resetErrors = () => Object.values(actions).forEach((m) => m.reset());

  const callNextBlocked = counter
    ? `Finish ${counter.displayToken} first`
    : b.status !== 'OPEN' ? 'Open the queue to call' : b.waiting.length === 0 ? 'Nobody is waiting' : null;

  const registerGuest = (event: FormEvent) => {
    event.preventDefault();
    if (!guestName.trim()) return;
    resetErrors();
    actions.guest.mutate({ guestName: guestName.trim(), priority: guestPriority }, { onSuccess: () => { setGuestName(''); setGuestPriority('NORMAL'); } });
  };

  const setStatus = (status: QueueStatus) => { resetErrors(); actions.status.mutate(status); };

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow={<Link to="/staff" className="hover:underline">Staff · your queues</Link>}
        title={<span className="flex flex-wrap items-center gap-3">{b.queueName} <QueueStatusBadge status={b.status} /></span>}
        subtitle={`Live board for ${b.serviceDate} (${b.timezone})`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <LiveIndicator status={stream} />
            <Button size="sm" variant={b.status === 'OPEN' ? 'primary' : 'secondary'} disabled={b.status === 'OPEN' || busy}
                    icon={<DoorOpen className="size-4" aria-hidden />} onClick={() => setStatus('OPEN')}>Open</Button>
            <Button size="sm" variant={b.status === 'PAUSED' ? 'primary' : 'secondary'} disabled={b.status === 'PAUSED' || busy}
                    icon={<PauseCircle className="size-4" aria-hidden />} onClick={() => setStatus('PAUSED')}>Pause</Button>
            <Button size="sm" variant={b.status === 'CLOSED' ? 'primary' : 'secondary'} disabled={b.status === 'CLOSED' || busy}
                    icon={<DoorClosed className="size-4" aria-hidden />} onClick={() => setStatus('CLOSED')}>Close</Button>
          </div>
        }
      />

      {errors.length > 0 && <Alert tone="error">{errors[0]}</Alert>}

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <h2 className="text-sm font-medium uppercase tracking-wide text-slate-500">At the counter</h2>
          {counter ? (
            <div className="mt-3 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-4">
                <div className="font-mono text-4xl font-bold text-indigo-600">{counter.displayToken}</div>
                <div>
                  <div className="font-medium text-slate-900">{counter.holderName}</div>
                  <div className="flex items-center gap-2 text-sm text-slate-600">
                    <TicketStatusBadge status={counter.status} />
                    {counter.status === 'CALLED' ? `called ${minutesSince(counter.calledAt)}` : `serving since ${formatTime(counter.serviceStartedAt, b.timezone)}`}
                  </div>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
                {counter.status === 'CALLED' && (
                  <>
                    <Button variant="success" loading={actions.start.isPending} disabled={busy} icon={<PlayCircle className="size-4" aria-hidden />}
                            onClick={() => { resetErrors(); actions.start.mutate(counter.ticketId); }}>Start service</Button>
                    <Button variant="secondary" loading={actions.noShow.isPending} disabled={busy} icon={<UserX className="size-4" aria-hidden />}
                            onClick={() => { resetErrors(); actions.noShow.mutate(counter.ticketId); }}>No-show</Button>
                  </>
                )}
                {counter.status === 'SERVING' && (
                  <Button variant="success" loading={actions.complete.isPending} disabled={busy} icon={<CheckCircle2 className="size-4" aria-hidden />}
                          onClick={() => { resetErrors(); actions.complete.mutate(counter.ticketId); }}>Complete</Button>
                )}
              </div>
            </div>
          ) : (
            <p className="mt-3 text-slate-600">The counter is free.</p>
          )}
        </Card>
        <Card className="flex flex-col justify-center gap-2">
          <Button size="lg" className="w-full" icon={<Megaphone className="size-5" aria-hidden />}
                  loading={actions.callNext.isPending} disabled={!!callNextBlocked || busy}
                  onClick={() => { resetErrors(); actions.callNext.mutate(undefined); }}>
            Call next
          </Button>
          <p className="text-center text-sm text-slate-500">{callNextBlocked ?? `Next: ${b.waiting[0]?.displayToken} · ${b.waiting[0]?.holderName}`}</p>
        </Card>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Waiting" value={b.stats.waiting} />
        <Stat label="Expected" value={b.stats.expected} hint="Booked, not checked in" />
        <Stat label="Completed" value={b.stats.completed} />
        <Stat label="No-shows" value={b.stats.noShow} />
        <Stat label="Cancelled" value={b.stats.cancelled} />
        <Stat label="Avg service" value={`${b.stats.averageServiceMinutes} min`}
              hint={b.stats.estimateBasis === 'HISTORY' ? `last ${b.stats.estimateSamples} services` : 'default (little history)'} />
      </div>

      <Card className="overflow-hidden p-0">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 px-5 py-4">
          <h2 className="font-semibold text-slate-900">Waiting line <span className="font-normal text-slate-500">({b.waiting.length})</span></h2>
          <p className="text-xs text-slate-500">{b.orderingPolicy}</p>
        </div>
        {b.waiting.length === 0 ? (
          <div className="p-5"><EmptyState title="Nobody is waiting">New walk-ins and checked-in appointments appear here instantly.</EmptyState></div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr><th className="px-5 py-2">#</th><th className="px-3 py-2">Token</th><th className="px-3 py-2">Name</th>
                  <th className="px-3 py-2">Source</th><th className="px-3 py-2">Priority</th><th className="px-3 py-2">In line since</th>
                  <th className="px-3 py-2">Est. wait</th><th className="px-3 py-2"><span className="sr-only">Actions</span></th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {b.waiting.map((t) => (
                  <tr key={t.ticketId} className={cx(t.priority === 'HIGH' && 'bg-rose-50/40')}>
                    <td className="px-5 py-2 font-medium text-slate-900">{t.position}</td>
                    <td className="px-3 py-2 font-mono font-semibold">{t.displayToken}</td>
                    <td className="px-3 py-2">{t.holderName}</td>
                    <td className="px-3 py-2 text-slate-600">{sourceLabel[t.source]}</td>
                    <td className="px-3 py-2">
                      <label className="sr-only" htmlFor={`priority-${t.ticketId}`}>Priority of {t.displayToken}</label>
                      <Select id={`priority-${t.ticketId}`} value={t.priority} disabled={busy} className="w-28 py-1"
                              onChange={(e) => { resetErrors(); actions.priority.mutate({ ticketId: t.ticketId, priority: e.target.value as TicketPriority }); }}>
                        {(['HIGH', 'NORMAL', 'LOW'] as const).map((p) => <option key={p} value={p}>{priorityLabel[p]}</option>)}
                      </Select>
                    </td>
                    <td className="px-3 py-2 text-slate-600">{formatTime(t.checkedInAt, b.timezone)}</td>
                    <td className="px-3 py-2 text-slate-600">{minutesLabel(t.estimatedWaitMinutes)}</td>
                    <td className="px-3 py-2 text-right">
                      <Button size="sm" variant="ghost" disabled={busy} icon={<XCircle className="size-4" aria-hidden />}
                              onClick={() => setCancelling(t)}>Cancel</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <h2 className="font-semibold text-slate-900">Expected appointments today <span className="font-normal text-slate-500">({b.expected.length})</span></h2>
          {b.expected.length === 0 ? (
            <p className="mt-2 text-sm text-slate-500">No booked customers are still expected today.</p>
          ) : (
            <ul className="mt-3 divide-y divide-slate-100">
              {b.expected.map((t) => (
                <li key={t.ticketId} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <div className="text-sm">
                    <span className="font-mono font-semibold">{t.displayToken}</span> · {t.holderName}
                    <span className="text-slate-500"> · {formatTime(t.scheduledAt, b.timezone)}</span>
                    {t.priority !== 'NORMAL' && <span className="ml-2"><PriorityBadge priority={t.priority} /></span>}
                  </div>
                  <div className="flex gap-2">
                    <Button size="sm" variant="secondary" disabled={busy || !t.appointmentId || b.status === 'CLOSED'}
                            onClick={() => { resetErrors(); if (t.appointmentId) actions.checkIn.mutate(t.appointmentId); }}>Check in</Button>
                    <Button size="sm" variant="ghost" disabled={busy} onClick={() => setCancelling(t)}>Cancel</Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card>
          <h2 className="font-semibold text-slate-900">Register a walk-in</h2>
          <p className="mt-1 text-sm text-slate-600">For visitors without an account. They get the next token for today.</p>
          <form onSubmit={registerGuest} className="mt-3 flex flex-col gap-2 sm:flex-row">
            <label htmlFor="guestName" className="sr-only">Visitor name</label>
            <Input id="guestName" placeholder="Visitor name" value={guestName} maxLength={120} onChange={(e) => setGuestName(e.target.value)} />
            <label htmlFor="guestPriority" className="sr-only">Priority</label>
            <Select id="guestPriority" value={guestPriority} className="sm:w-32" onChange={(e) => setGuestPriority(e.target.value as TicketPriority)}>
              {(['NORMAL', 'HIGH', 'LOW'] as const).map((p) => <option key={p} value={p}>{priorityLabel[p]}</option>)}
            </Select>
            <Button type="submit" loading={actions.guest.isPending} disabled={!guestName.trim() || busy || b.status === 'CLOSED'}
                    icon={<UserPlus className="size-4" aria-hidden />}>Add</Button>
          </form>
        </Card>
      </div>

      <ConfirmDialog open={!!cancelling} title={`Cancel ${cancelling?.displayToken ?? ''}?`} confirmLabel="Cancel ticket"
                     loading={actions.cancel.isPending} onCancel={() => setCancelling(null)}
                     onConfirm={() => cancelling && actions.cancel.mutate(cancelling.ticketId, { onSettled: () => setCancelling(null) })}>
        {cancelling?.holderName} will be removed from today’s queue{cancelling?.appointmentId ? ' and the appointment is cancelled' : ''}.
        The customer is notified.
      </ConfirmDialog>
    </div>
  );
}
