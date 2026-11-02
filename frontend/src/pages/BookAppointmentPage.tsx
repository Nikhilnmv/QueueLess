import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { errorMessage, isApiError } from '../api/client';
import { useBookAppointment, useQueue, useSlots } from '../api/queries';
import type { Slot } from '../api/types';
import { LiveIndicator } from '../components/StatusBadges';
import { Button, Empty, ErrorNotice, Loading, Notice, PageTitle, Panel, cx } from '../components/ui';
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

  // One idempotency key per chosen slot: a retry or a double click reuses it, so the server
  // returns the booking it already made instead of creating a second one.
  const idempotencyKey = useMemo(() => (selected ? newIdempotencyKey() : ''), [selected?.startsAt]);

  const dates = useMemo(() => {
    if (!slots.data) return [];
    const list: string[] = [];
    for (let d = slots.data.firstBookableDate; d <= slots.data.lastBookableDate; d = addDays(d, 1)) list.push(d);
    return list;
  }, [slots.data?.firstBookableDate, slots.data?.lastBookableDate]);

  if (queue.isPending) return <Loading />;
  if (queue.isError) return <ErrorNotice error={queue.error} onRetry={() => queue.refetch()} />;
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
    <div className="space-y-5">
      <PageTitle
        back={<Link to={`/queues/${queueId}`} className="text-pine-700 hover:underline">{q.organizationName} · {q.name}</Link>}
        title="Book an appointment"
        description={`Slots are ${q.slotDurationMinutes} minutes, from ${formatLocalTime(q.openingTime)} to ${formatLocalTime(q.closingTime)} (${q.timezone}).`}
        actions={<LiveIndicator status={stream} />}
      />

      {!q.bookingEnabled && <Notice tone="warning">This counter is not taking bookings at the moment.</Notice>}

      <Panel title="1. Day">
        <div className="flex gap-2 overflow-x-auto pb-1" role="listbox" aria-label="Day">
          {dates.map((d) => (
            <button key={d} type="button" role="option" aria-selected={d === activeDate}
                    onClick={() => { setDate(d); setSelected(null); book.reset(); }}
                    className={cx('shrink-0 rounded border px-3 py-1.5 text-sm',
                      d === activeDate ? 'border-pine-700 bg-pine-700 text-white' : 'border-stone-300 bg-white text-stone-700 hover:bg-stone-50')}>
              {formatLocalDate(d, 'short')}
            </button>
          ))}
        </div>
      </Panel>

      <Panel title="2. Time">
        {slots.isPending && <Loading label="Loading times" />}
        {slots.isError && <ErrorNotice error={slots.error} onRetry={() => slots.refetch()} />}
        {slots.data && slots.data.slots.every((s) => !s.bookable) && (
          <div className="mb-3"><Empty>No free times on this day. Try another day.</Empty></div>
        )}
        {slots.data && (
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-6 lg:grid-cols-8">
            {slots.data.slots.map((slot) => {
              const isSelected = selected?.startsAt === slot.startsAt;
              return (
                <button key={slot.startsAt} type="button" disabled={!slot.bookable}
                        onClick={() => { setSelected(slot); book.reset(); }}
                        aria-pressed={isSelected}
                        aria-label={`${formatLocalTime(slot.localTime)}, ${slot.available} of ${slot.capacity} places left`}
                        className={cx('rounded border px-2 py-1.5 text-center text-sm tabular',
                          isSelected ? 'border-pine-700 bg-pine-700 text-white'
                            : slot.bookable ? 'border-stone-300 bg-white text-stone-800 hover:border-pine-600'
                              : 'cursor-not-allowed border-stone-200 bg-stone-100 text-stone-400 line-through')}>
                  {formatLocalTime(slot.localTime)}
                  <span className={cx('block text-xs', isSelected ? 'text-pine-100' : 'text-stone-500')}>
                    {slot.available > 0 ? `${slot.available} left` : 'full'}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </Panel>

      <Panel title="3. Confirm">
        {book.isError && <div className="mb-3"><Notice tone="error">{errorMessage(book.error)}</Notice></div>}
        {selected ? (
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="text-sm text-stone-700">
              <p className="text-base font-medium text-stone-900">{formatDateTime(selected.startsAt, q.timezone)}</p>
              <p>{q.name}, {q.organizationName}</p>
              {freshSelected && <p className="text-stone-500">{freshSelected.available} of {freshSelected.capacity} places left in this slot.</p>}
            </div>
            <Button size="lg" onClick={confirm} loading={book.isPending} disabled={freshSelected?.bookable === false}>
              Confirm booking
            </Button>
          </div>
        ) : (
          <p className="text-sm text-stone-500">Choose a time above.</p>
        )}
      </Panel>
    </div>
  );
}
