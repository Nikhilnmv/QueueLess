import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { CalendarCheck, CalendarClock } from 'lucide-react';
import { errorMessage, isApiError } from '../api/client';
import { useBookAppointment, useQueue, useSlots } from '../api/queries';
import type { Slot } from '../api/types';
import { LiveIndicator } from '../components/StatusBadges';
import { Alert, Button, Card, EmptyState, ErrorState, PageHeader, Spinner, cx } from '../components/ui';
import { addDays, formatDateTime, formatLocalDate, formatLocalTime, newIdempotencyKey } from '../lib/format';
import { useQueueEvents } from '../realtime/hooks';

export function BookAppointmentPage() {
  const { queueId = '' } = useParams();
  const queue = useQueue(queueId);
  const [date, setDate] = useState<string | null>(null);
  const slots = useSlots(queueId, date);
  const [selected, setSelected] = useState<Slot | null>(null);
  const book = useBookAppointment(queueId);
  const navigate = useNavigate();
  const stream = useQueueEvents(queueId);

  // One idempotency key per chosen slot: retries and double clicks reuse it, so the server
  // returns the booking it already made instead of creating a second one.
  const idempotencyKey = useMemo(() => (selected ? newIdempotencyKey() : ''), [selected?.startsAt]);

  const dates = useMemo(() => {
    if (!slots.data) return [];
    const list: string[] = [];
    for (let d = slots.data.firstBookableDate; d <= slots.data.lastBookableDate; d = addDays(d, 1)) list.push(d);
    return list;
  }, [slots.data?.firstBookableDate, slots.data?.lastBookableDate]);

  if (queue.isPending) return <Spinner />;
  if (queue.isError) return <ErrorState error={queue.error} onRetry={() => queue.refetch()} />;
  const q = queue.data;
  const activeDate = date ?? slots.data?.date ?? null;
  const freshSelected = selected && slots.data?.slots.find((s) => s.startsAt === selected.startsAt);

  const confirm = () => {
    if (!selected) return;
    book.mutate({ scheduledAt: selected.startsAt, idempotencyKey }, {
      onSuccess: (appointment) => navigate(`/appointments/${appointment.id}?booked=1`),
      onError: (error) => {
        if (isApiError(error, 'SLOT_FULL')) setSelected(null);
      },
    });
  };

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow={<Link to={`/queues/${queueId}`} className="hover:underline">{q.organizationName} · {q.name}</Link>}
        title="Book an appointment"
        subtitle={`${q.slotDurationMinutes}-minute slots between ${formatLocalTime(q.openingTime)} and ${formatLocalTime(q.closingTime)} (${q.timezone}).`}
        actions={<LiveIndicator status={stream} />}
      />

      {!q.bookingEnabled && <Alert tone="warning">This queue is not taking appointment bookings right now.</Alert>}

      <Card>
        <h2 className="font-semibold text-slate-900">1. Choose a day</h2>
        <div className="mt-3 flex gap-2 overflow-x-auto pb-2" role="listbox" aria-label="Day">
          {dates.map((d) => (
            <button key={d} type="button" role="option" aria-selected={d === activeDate}
                    onClick={() => { setDate(d); setSelected(null); book.reset(); }}
                    className={cx('shrink-0 rounded-lg px-3 py-2 text-sm font-medium ring-1 ring-inset',
                      d === activeDate ? 'bg-indigo-600 text-white ring-indigo-600' : 'bg-white text-slate-700 ring-slate-300 hover:bg-slate-50')}>
              {formatLocalDate(d, 'short')}
            </button>
          ))}
        </div>
      </Card>

      <Card>
        <h2 className="font-semibold text-slate-900">2. Choose a time</h2>
        {slots.isPending && <Spinner label="Loading slots" />}
        {slots.isError && <ErrorState error={slots.error} onRetry={() => slots.refetch()} />}
        {slots.data && slots.data.slots.every((s) => !s.bookable) && (
          <div className="mt-3">
            <EmptyState icon={<CalendarClock className="size-8" />} title="No free slots on this day">Try another day.</EmptyState>
          </div>
        )}
        {slots.data && (
          <div className="mt-3 grid grid-cols-3 gap-2 sm:grid-cols-5 lg:grid-cols-8">
            {slots.data.slots.map((slot) => {
              const isSelected = selected?.startsAt === slot.startsAt;
              return (
                <button key={slot.startsAt} type="button" disabled={!slot.bookable}
                        onClick={() => { setSelected(slot); book.reset(); }}
                        aria-pressed={isSelected}
                        aria-label={`${formatLocalTime(slot.localTime)}, ${slot.available} of ${slot.capacity} places left`}
                        className={cx('rounded-lg px-2 py-2 text-center text-sm ring-1 ring-inset transition-colors',
                          isSelected ? 'bg-indigo-600 text-white ring-indigo-600'
                            : slot.bookable ? 'bg-white text-slate-800 ring-slate-300 hover:bg-indigo-50'
                              : 'cursor-not-allowed bg-slate-100 text-slate-400 ring-slate-200')}>
                  <div className="font-medium">{formatLocalTime(slot.localTime)}</div>
                  <div className={cx('text-xs', isSelected ? 'text-indigo-100' : 'text-slate-500')}>
                    {slot.available > 0 ? `${slot.available} left` : 'Full'}
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </Card>

      <Card>
        <h2 className="font-semibold text-slate-900">3. Confirm</h2>
        {book.isError && <div className="mt-3"><Alert tone="error">{errorMessage(book.error)}</Alert></div>}
        {selected ? (
          <div className="mt-3 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="text-sm text-slate-700">
              <div className="text-base font-medium text-slate-900">{formatDateTime(selected.startsAt, q.timezone)}</div>
              <div>{q.name} at {q.organizationName}</div>
              <div className="text-slate-500">
                {freshSelected ? `${freshSelected.available} of ${freshSelected.capacity} places left in this slot.` : ''} A token is reserved
                for you when you confirm.
              </div>
            </div>
            <Button size="lg" onClick={confirm} loading={book.isPending} disabled={freshSelected?.bookable === false}
                    icon={<CalendarCheck className="size-5" aria-hidden />}>
              Confirm booking
            </Button>
          </div>
        ) : (
          <p className="mt-2 text-sm text-slate-500">Select a time above.</p>
        )}
      </Card>
    </div>
  );
}
