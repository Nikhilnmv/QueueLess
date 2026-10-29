import { Link } from 'react-router';
import { CalendarClock, ChevronRight, Ticket as TicketIcon } from 'lucide-react';
import { useMe, useMyAppointments, useMyTickets } from '../api/queries';
import type { Appointment } from '../api/types';
import { AppointmentStatusBadge } from '../components/StatusBadges';
import { TicketCard } from '../components/TicketCard';
import { Card, EmptyState, ErrorState, PageHeader, Spinner } from '../components/ui';
import { formatDateTime, todayIn } from '../lib/format';
import { useQueueEvents } from '../realtime/hooks';

/** Keeps a queue's data fresh while the dashboard shows a ticket from it. */
function QueueSubscription({ queueId }: { queueId: string }) {
  useQueueEvents(queueId);
  return null;
}

function AppointmentRow({ appointment }: { appointment: Appointment }) {
  return (
    <Link to={`/appointments/${appointment.id}`} className="group">
      <Card className="flex items-center gap-4 transition-shadow group-hover:shadow-md">
        <CalendarClock className="size-8 shrink-0 text-indigo-600" aria-hidden />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium text-slate-900">{appointment.queueName}</span>
            <AppointmentStatusBadge status={appointment.status} />
            {appointment.canCheckIn && <span className="text-xs font-medium text-emerald-700">Check-in open</span>}
          </div>
          <div className="text-sm text-slate-600">
            {appointment.organizationName} · {formatDateTime(appointment.scheduledAt, appointment.timezone)}
          </div>
          <div className="text-xs text-slate-500">Ref {appointment.bookingReference}{appointment.displayToken && ` · token ${appointment.displayToken}`}</div>
        </div>
        <ChevronRight className="size-5 shrink-0 text-slate-400" aria-hidden />
      </Card>
    </Link>
  );
}

export function DashboardPage() {
  const me = useMe();
  const tickets = useMyTickets();
  const appointments = useMyAppointments();

  const activeTickets = tickets.data?.filter((t) => !t.expired && t.live !== null && ['WAITING', 'CALLED', 'SERVING'].includes(t.status)) ?? [];
  const upcoming = appointments.data?.filter((a) => a.status === 'BOOKED' && a.serviceDate >= todayIn(a.timezone)) ?? [];
  const pastTickets = tickets.data?.filter((t) => !activeTickets.includes(t) && t.status !== 'BOOKED') ?? [];
  const pastAppointments = appointments.data?.filter((a) => !upcoming.includes(a)) ?? [];
  const watchedQueues = [...new Set(activeTickets.map((t) => t.queueId))];

  return (
    <div className="space-y-8">
      {watchedQueues.map((queueId) => <QueueSubscription key={queueId} queueId={queueId} />)}
      <PageHeader title={`Hello, ${me.data?.fullName ?? ''}`} subtitle="Your tokens, appointments and history."
                  actions={<Link to="/organizations" className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">Find a service</Link>} />

      <section aria-labelledby="active">
        <h2 id="active" className="mb-3 text-lg font-semibold text-slate-900">In line now</h2>
        {tickets.isPending && <Spinner />}
        {tickets.isError && <ErrorState error={tickets.error} onRetry={() => tickets.refetch()} />}
        {tickets.data && activeTickets.length === 0 && (
          <EmptyState icon={<TicketIcon className="size-8" />} title="You are not in any queue">
            Join a queue as a walk-in, or check in to today’s appointment to get in line.
          </EmptyState>
        )}
        <div className="grid gap-3">{activeTickets.map((ticket) => <TicketCard key={ticket.id} ticket={ticket} />)}</div>
      </section>

      <section aria-labelledby="upcoming">
        <h2 id="upcoming" className="mb-3 text-lg font-semibold text-slate-900">Upcoming appointments</h2>
        {appointments.isPending && <Spinner />}
        {appointments.isError && <ErrorState error={appointments.error} onRetry={() => appointments.refetch()} />}
        {appointments.data && upcoming.length === 0 && (
          <EmptyState icon={<CalendarClock className="size-8" />} title="No upcoming appointments">
            Book a slot from any queue that accepts appointments.
          </EmptyState>
        )}
        <div className="grid gap-3">{upcoming.map((a) => <AppointmentRow key={a.id} appointment={a} />)}</div>
      </section>

      {(pastTickets.length > 0 || pastAppointments.length > 0) && (
        <section aria-labelledby="history">
          <h2 id="history" className="mb-3 text-lg font-semibold text-slate-900">History</h2>
          <div className="grid gap-3">
            {pastAppointments.map((a) => <AppointmentRow key={a.id} appointment={a} />)}
            {pastTickets.filter((t) => !t.appointmentId).map((t) => <TicketCard key={t.id} ticket={t} />)}
          </div>
        </section>
      )}
    </div>
  );
}
