import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { CalendarCheck, CalendarX, LogIn } from 'lucide-react';
import { errorMessage } from '../api/client';
import { useAppointment, useCancelAppointment, useCheckIn } from '../api/queries';
import { AppointmentStatusBadge, LiveIndicator, TicketStatusBadge } from '../components/StatusBadges';
import { Alert, Button, Card, ConfirmDialog, ErrorState, Spinner } from '../components/ui';
import { formatDateTime, formatTime } from '../lib/format';
import { useQueueEvents } from '../realtime/hooks';

export function AppointmentPage() {
  const { appointmentId = '' } = useParams();
  const [params] = useSearchParams();
  const appointment = useAppointment(appointmentId);
  const checkIn = useCheckIn();
  const cancel = useCancelAppointment();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const active = appointment.data && (appointment.data.status === 'BOOKED' || appointment.data.status === 'CHECKED_IN');
  const stream = useQueueEvents(appointment.data?.queueId, !!active);

  if (appointment.isPending) return <Spinner label="Loading appointment" />;
  if (appointment.isError) return <ErrorState error={appointment.error} onRetry={() => appointment.refetch()} />;
  const a = appointment.data;
  const justBooked = params.get('booked') === '1' && a.status === 'BOOKED';

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link to={`/queues/${a.queueId}`} className="text-sm font-medium text-indigo-600 hover:underline">{a.organizationName} · {a.queueName}</Link>
          <h1 className="text-2xl font-semibold text-slate-900">Appointment {a.bookingReference}</h1>
        </div>
        <div className="flex items-center gap-2">
          <AppointmentStatusBadge status={a.status} />
          {active && <LiveIndicator status={stream} />}
        </div>
      </div>

      {justBooked && (
        <Alert tone="success" title="Booking confirmed">
          Your token {a.displayToken} is reserved. A confirmation is saved in your notifications.
        </Alert>
      )}

      <Card>
        <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
          <div><dt className="text-slate-500">When</dt><dd className="text-base font-medium text-slate-900">{formatDateTime(a.scheduledAt, a.timezone)}</dd></div>
          <div><dt className="text-slate-500">Token</dt><dd className="font-mono text-base font-semibold text-indigo-600">{a.displayToken ?? '—'}</dd></div>
          <div><dt className="text-slate-500">Check-in opens</dt><dd>{formatDateTime(a.checkInOpensAt, a.timezone)}</dd></div>
          <div><dt className="text-slate-500">Ticket</dt><dd>{a.ticketStatus ? <TicketStatusBadge status={a.ticketStatus} /> : '—'}</dd></div>
          {a.cancelledAt && <div><dt className="text-slate-500">Cancelled</dt><dd>{formatTime(a.cancelledAt, a.timezone)}</dd></div>}
          <div><dt className="text-slate-500">Time zone</dt><dd>{a.timezone}</dd></div>
        </dl>
      </Card>

      {a.status === 'BOOKED' && !a.canCheckIn && (
        <p className="text-sm text-slate-600">
          You can check in from {formatDateTime(a.checkInOpensAt, a.timezone)} when you arrive. Your place in the line is
          decided by your check-in time.
        </p>
      )}

      {checkIn.isError && <Alert tone="error">{errorMessage(checkIn.error)}</Alert>}
      {cancel.isError && <Alert tone="error">{errorMessage(cancel.error)}</Alert>}

      <div className="flex flex-wrap gap-3">
        {a.canCheckIn && (
          <Button size="lg" variant="success" loading={checkIn.isPending} icon={<LogIn className="size-5" aria-hidden />}
                  onClick={() => checkIn.mutate({ id: a.id, queueId: a.queueId }, { onSuccess: (ticket) => navigate(`/tickets/${ticket.id}`) })}>
            I’m here — check in
          </Button>
        )}
        {a.status === 'CHECKED_IN' && a.ticketId && (
          <Link to={`/tickets/${a.ticketId}`} className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">
            <CalendarCheck className="size-4" aria-hidden /> Follow my position
          </Link>
        )}
        {a.canCancel && (
          <Button variant="secondary" icon={<CalendarX className="size-4" aria-hidden />} onClick={() => setConfirming(true)}>
            Cancel appointment
          </Button>
        )}
        <Link to="/dashboard" className="rounded-lg px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100">Back to my queue</Link>
      </div>

      <ConfirmDialog open={confirming} title="Cancel this appointment?" confirmLabel="Cancel appointment" loading={cancel.isPending}
                     onCancel={() => setConfirming(false)}
                     onConfirm={() => cancel.mutate({ id: a.id, queueId: a.queueId }, { onSettled: () => setConfirming(false) })}>
        The slot on {formatDateTime(a.scheduledAt, a.timezone)} is released and token {a.displayToken} is cancelled.
      </ConfirmDialog>
    </div>
  );
}
