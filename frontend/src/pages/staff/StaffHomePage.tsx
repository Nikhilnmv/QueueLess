import { Link } from 'react-router';
import { useManagedQueues, useMe, useQueueLive } from '../../api/queries';
import type { MembershipSummary, Queue } from '../../api/types';
import { staffMemberships } from '../../auth/roles';
import { QueueStatusBadge } from '../../components/StatusBadges';
import { Empty, ErrorNotice, Loading, PageTitle, table, td, th } from '../../components/ui';

function QueueRow({ queue }: { queue: Queue }) {
  const live = useQueueLive(queue.id);
  return (
    <tr className="hover:bg-stone-50">
      <td className={td}>
        <Link to={`/staff/queues/${queue.id}`} className="font-medium text-pine-700 hover:underline">{queue.name}</Link>
      </td>
      <td className={td}><QueueStatusBadge status={queue.status} /></td>
      <td className={`${td} tabular`}>{live.data ? live.data.waitingCount : '…'}</td>
      <td className={`${td} font-mono`}>{live.data ? (live.data.nowServing?.displayToken ?? 'nobody') : '…'}</td>
    </tr>
  );
}

function OrganizationQueues({ membership }: { membership: MembershipSummary }) {
  const queues = useManagedQueues(membership.organizationId);
  return (
    <section aria-labelledby={`org-${membership.organizationId}`}>
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={`org-${membership.organizationId}`} className="font-semibold text-stone-900">{membership.organizationName}</h2>
        <Link to={`/admin/organizations/${membership.organizationId}`} className="text-sm text-pine-700 hover:underline">
          {membership.role === 'ADMIN' ? 'Manage organization' : 'Organization overview'}
        </Link>
      </div>
      {queues.isPending && <Loading />}
      {queues.isError && <ErrorNotice error={queues.error} onRetry={() => queues.refetch()} />}
      {queues.data?.length === 0 && <Empty>No queues yet. An administrator can create them.</Empty>}
      {queues.data && queues.data.length > 0 && (
        <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
          <table className={table}>
            <thead>
              <tr><th className={th}>Queue</th><th className={th}>Status</th><th className={th}>Waiting</th><th className={th}>Now serving</th></tr>
            </thead>
            <tbody>{queues.data.map((q) => <QueueRow key={q.id} queue={q} />)}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export function StaffHomePage() {
  const me = useMe();
  const memberships = staffMemberships(me.data);
  return (
    <div className="space-y-8">
      <PageTitle title="Staff" description="Open a queue to call, serve and manage visitors." />
      {memberships.map((m) => <OrganizationQueues key={m.organizationId} membership={m} />)}
    </div>
  );
}
