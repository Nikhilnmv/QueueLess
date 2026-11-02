import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { errorMessage } from '../api/client';
import { useAppointment, useCancelAppointment, useCheckIn } from '../api/queries';
import { AppointmentStatusBadge, LiveIndicator, TicketStatusBadge } from '../components/StatusBadges';
import { Button, ConfirmDialog, Details, ErrorNotice, Loading, Notice, PageTitle, Panel } from '../components/ui';
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

  if (appointment.isPending) return <Loading label="Loading appointment" />;
  if (appointment.isError) return <ErrorNotice error={appointment.error} onRetry={() => appointment.refetch()} />;
  const a = appointment.data;
  const justBooked = params.get('booked') === '1' && a.status === 'BOOKED';

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <PageTitle
        back={<Link to="/dashboard" className="text-pine-700 hover:underline">Back to my visits</Link>}
        title={`Appointment ${a.bookingReference}`}
        description={`${a.queueName}, ${a.organizationName}`}
        actions={<><AppointmentStatusBadge status={a.status} />{active && <LiveIndicator status={stream} />}</>}
      />

      {justBooked && (
        <Notice tone="success" title="Booking confirmed">
          Your token {a.displayToken} is reserved. A copy of the confirmation is in your notifications.
        </Notice>
      )}

      <Panel title="Booking">
        <Details rows={[
          ['Date and time', <span className="font-medium">{formatDateTime(a.scheduledAt, a.timezone)}</span>],
          ['Token', <span className="font-mono font-medium text-pine-700">{a.displayToken ?? 'none'}</span>],
          ['Check-in opens', formatDateTime(a.checkInOpensAt, a.timezone)],
          ['Ticket', a.ticketStatus ? <TicketStatusBadge status={a.ticketStatus} /> : 'none'],
          ...(a.cancelledAt ? [['Cancelled at', formatTime(a.cancelledAt, a.timezone)] as [string, string]] : []),
          ['Time zone', a.timezone],
        ]} />
      </Panel>

      {a.status === 'BOOKED' && !a.canCheckIn && (
        <p className="text-sm text-stone-600">
          Check in when you arrive, from {formatDateTime(a.checkInOpensAt, a.timezone)}. Your place in line counts from check-in.
        </p>
      )}

      {checkIn.isError && <Notice tone="error">{errorMessage(checkIn.error)}</Notice>}
      {cancel.isError && <Notice tone="error">{errorMessage(cancel.error)}</Notice>}

      <div className="flex flex-wrap items-center gap-3">
        {a.canCheckIn && (
          <Button size="lg" loading={checkIn.isPending}
                  onClick={() => checkIn.mutate({ id: a.id, queueId: a.queueId }, { onSuccess: (ticket) => navigate(`/tickets/${ticket.id}`) })}>
            I have arrived, check me in
          </Button>
        )}
        {a.status === 'CHECKED_IN' && a.ticketId && (
          <Link to={`/tickets/${a.ticketId}`} className="rounded bg-pine-700 px-3.5 py-2 text-sm font-medium text-white hover:bg-pine-800">
            See my place in line
          </Link>
        )}
        {a.canCancel && <Button variant="danger" onClick={() => setConfirming(true)}>Cancel appointment</Button>}
      </div>

      <ConfirmDialog open={confirming} title="Cancel this appointment?" confirmLabel="Cancel appointment" loading={cancel.isPending}
                     onCancel={() => setConfirming(false)}
                     onConfirm={() => cancel.mutate({ id: a.id, queueId: a.queueId }, { onSettled: () => setConfirming(false) })}>
        The slot on {formatDateTime(a.scheduledAt, a.timezone)} becomes free and token {a.displayToken} is cancelled.
      </ConfirmDialog>
    </div>
  );
}
