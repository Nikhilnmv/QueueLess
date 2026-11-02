import { Link } from 'react-router';
import { useMe, useMyAppointments, useMyTickets } from '../api/queries';
import type { Appointment, Ticket } from '../api/types';
import { AppointmentStatusBadge, TicketStatusBadge } from '../components/StatusBadges';
import { Empty, ErrorNotice, Loading, PageTitle, table, td, th } from '../components/ui';
import { formatDateTime, formatLocalDate, minutesLabel, todayIn } from '../lib/format';
import { useQueueEvents } from '../realtime/hooks';

/** Keeps a queue's numbers fresh while one of its tickets is shown here. */
function QueueSubscription({ queueId }: { queueId: string }) {
  useQueueEvents(queueId);
  return null;
}

function ActiveTicket({ ticket }: { ticket: Ticket }) {
  const live = ticket.live;
  const atCounter = ticket.status === 'CALLED' || ticket.status === 'SERVING';
  return (
    <Link to={`/tickets/${ticket.id}`}
          className="flex items-center gap-5 rounded-md border border-stone-200 bg-white px-4 py-3 hover:border-pine-600">
      <span className="font-mono text-2xl font-medium text-pine-700">{ticket.displayToken}</span>
      <span className="min-w-0 flex-1">
        <span className="block font-medium text-stone-900">{ticket.queueName}</span>
        <span className="block text-sm text-stone-500">{ticket.organizationName}</span>
      </span>
      <span className="text-right text-sm">
        {atCounter
          ? <span className="font-medium text-pine-700">Your turn now</span>
          : live && live.position !== null
            ? <>Number {live.position} in line<span className="block text-stone-500">about {minutesLabel(live.estimatedWaitMinutes).replace('~', '')}</span></>
            : <TicketStatusBadge status={ticket.status} />}
      </span>
    </Link>
  );
}

function AppointmentRows({ appointments }: { appointments: Appointment[] }) {
  return (
    <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
      <table className={table}>
        <thead>
          <tr><th className={th}>When</th><th className={th}>Counter</th><th className={th}>Token</th><th className={th}>Status</th><th className={th}><span className="sr-only">Open</span></th></tr>
        </thead>
        <tbody>
          {appointments.map((a) => (
            <tr key={a.id} className="hover:bg-stone-50">
              <td className={`${td} whitespace-nowrap`}>{formatDateTime(a.scheduledAt, a.timezone)}</td>
              <td className={td}>{a.queueName}<span className="block text-xs text-stone-500">{a.organizationName}</span></td>
              <td className={`${td} font-mono`}>{a.displayToken ?? ''}</td>
              <td className={td}><AppointmentStatusBadge status={a.status} />{a.canCheckIn && <span className="ml-2 text-xs text-pine-700">check-in open</span>}</td>
              <td className={`${td} text-right`}><Link to={`/appointments/${a.id}`} className="text-pine-700 hover:underline">{a.queueName}</Link></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function DashboardPage() {
  const me = useMe();
  const tickets = useMyTickets();
  const appointments = useMyAppointments();

  const activeTickets = tickets.data?.filter((t) => !t.expired && t.live !== null && ['WAITING', 'CALLED', 'SERVING'].includes(t.status)) ?? [];
  const upcoming = appointments.data?.filter((a) => a.status === 'BOOKED' && a.serviceDate >= todayIn(a.timezone)) ?? [];
  const pastAppointments = appointments.data?.filter((a) => !upcoming.includes(a)) ?? [];
  const pastTickets = tickets.data?.filter((t) => !activeTickets.includes(t) && t.status !== 'BOOKED' && !t.appointmentId) ?? [];
  const watchedQueues = [...new Set(activeTickets.map((t) => t.queueId))];

  return (
    <div className="space-y-8">
      {watchedQueues.map((queueId) => <QueueSubscription key={queueId} queueId={queueId} />)}
      <PageTitle title="My visits" description={me.data ? `Signed in as ${me.data.fullName}` : undefined}
                 actions={<Link to="/organizations" className="rounded bg-pine-700 px-3.5 py-2 text-sm font-medium text-white hover:bg-pine-800">Find a service</Link>} />

      <section>
        <h2 className="mb-2 font-semibold text-stone-900">In line now</h2>
        {tickets.isPending && <Loading />}
        {tickets.isError && <ErrorNotice error={tickets.error} onRetry={() => tickets.refetch()} />}
        {tickets.data && activeTickets.length === 0 && (
          <Empty>You are not in any queue. Take a token at a counter, or check in for today's appointment.</Empty>
        )}
        <div className="space-y-2">{activeTickets.map((ticket) => <ActiveTicket key={ticket.id} ticket={ticket} />)}</div>
      </section>

      <section>
        <h2 className="mb-2 font-semibold text-stone-900">Upcoming appointments</h2>
        {appointments.isPending && <Loading />}
        {appointments.isError && <ErrorNotice error={appointments.error} onRetry={() => appointments.refetch()} />}
        {appointments.data && upcoming.length === 0 && <Empty>No upcoming appointments.</Empty>}
        {upcoming.length > 0 && <AppointmentRows appointments={upcoming} />}
      </section>

      {(pastAppointments.length > 0 || pastTickets.length > 0) && (
        <section className="space-y-3">
          <h2 className="font-semibold text-stone-900">Past visits</h2>
          {pastAppointments.length > 0 && <AppointmentRows appointments={pastAppointments} />}
          {pastTickets.length > 0 && (
            <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
              <table className={table}>
                <thead><tr><th className={th}>Day</th><th className={th}>Counter</th><th className={th}>Token</th><th className={th}>Status</th></tr></thead>
                <tbody>
                  {pastTickets.map((t) => (
                    <tr key={t.id} className="hover:bg-stone-50">
                      <td className={`${td} whitespace-nowrap`}>{formatLocalDate(t.serviceDate, 'short')}</td>
                      <td className={td}><Link to={`/tickets/${t.id}`} className="text-pine-700 hover:underline">{t.queueName}</Link></td>
                      <td className={`${td} font-mono`}>{t.displayToken}</td>
                      <td className={td}><TicketStatusBadge status={t.status} expired={t.expired} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
