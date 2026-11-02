import { Link, useParams } from 'react-router';
import { useOrganization, useOrganizationQueues, useQueueLive } from '../api/queries';
import type { Queue } from '../api/types';
import { QueueStatusBadge } from '../components/StatusBadges';
import { Empty, ErrorNotice, Loading, PageTitle, table, td, th } from '../components/ui';
import { formatLocalTime, minutesLabel } from '../lib/format';

function QueueRow({ queue }: { queue: Queue }) {
  const live = useQueueLive(queue.id);
  return (
    <tr className="hover:bg-stone-50">
      <td className={td}>
        <Link to={`/queues/${queue.id}`} className="font-medium text-pine-700 hover:underline">{queue.name}</Link>
        {queue.description && <div className="text-xs text-stone-500">{queue.description}</div>}
      </td>
      <td className={td}><QueueStatusBadge status={queue.status} /></td>
      <td className={`${td} whitespace-nowrap`}>{formatLocalTime(queue.openingTime)} to {formatLocalTime(queue.closingTime)}</td>
      <td className={td}>{live.data?.waitingCount ?? '…'}</td>
      <td className={`${td} whitespace-nowrap`}>{live.data ? minutesLabel(live.data.estimatedWaitMinutesForNewTicket) : '…'}</td>
      <td className={`${td} text-stone-600`}>{queue.bookingEnabled ? `${queue.slotDurationMinutes}-min slots` : 'Walk-in only'}</td>
    </tr>
  );
}

export function OrganizationPage() {
  const { slug = '' } = useParams();
  const organization = useOrganization(slug);
  const queues = useOrganizationQueues(slug);

  if (organization.isPending) return <Loading />;
  if (organization.isError) return <ErrorNotice error={organization.error} onRetry={() => organization.refetch()} />;

  return (
    <div>
      <PageTitle
        back={<Link to="/organizations" className="text-pine-700 hover:underline">All services</Link>}
        title={organization.data.name}
        description={organization.data.description}
      />
      {queues.isPending && <Loading label="Loading counters" />}
      {queues.isError && <ErrorNotice error={queues.error} onRetry={() => queues.refetch()} />}
      {queues.data?.length === 0 && <Empty>This organization has not opened any counters yet.</Empty>}
      {queues.data && queues.data.length > 0 && (
        <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
          <table className={table}>
            <thead>
              <tr>
                <th className={th}>Counter</th><th className={th}>Status</th><th className={th}>Hours</th>
                <th className={th}>Waiting</th><th className={th}>Wait if you join now</th><th className={th}>Appointments</th>
              </tr>
            </thead>
            <tbody>{queues.data.map((queue) => <QueueRow key={queue.id} queue={queue} />)}</tbody>
          </table>
        </div>
      )}
      <p className="mt-3 text-xs text-stone-500">Times are in {organization.data.timezone}. Waiting times are estimates.</p>
    </div>
  );
}
