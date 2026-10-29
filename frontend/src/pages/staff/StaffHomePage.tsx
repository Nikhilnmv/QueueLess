import { Link } from 'react-router';
import { ChevronRight, LayoutDashboard, Settings } from 'lucide-react';
import { useManagedQueues, useMe, useQueueLive } from '../../api/queries';
import type { MembershipSummary, Queue } from '../../api/types';
import { staffMemberships } from '../../auth/roles';
import { QueueStatusBadge } from '../../components/StatusBadges';
import { Card, EmptyState, ErrorState, PageHeader, Spinner } from '../../components/ui';

function QueueTile({ queue }: { queue: Queue }) {
  const live = useQueueLive(queue.id);
  return (
    <Link to={`/staff/queues/${queue.id}`} className="group">
      <Card className="flex items-center justify-between gap-4 transition-shadow group-hover:shadow-md">
        <div>
          <div className="flex items-center gap-2">
            <span className="font-medium text-slate-900 group-hover:text-indigo-700">{queue.name}</span>
            <QueueStatusBadge status={queue.status} />
          </div>
          <div className="mt-1 text-sm text-slate-600">
            {live.data
              ? `${live.data.waitingCount} waiting · now serving ${live.data.nowServing?.displayToken ?? 'nobody'}`
              : 'Loading live status…'}
          </div>
        </div>
        <ChevronRight className="size-5 text-slate-400" aria-hidden />
      </Card>
    </Link>
  );
}

function OrganizationQueues({ membership }: { membership: MembershipSummary }) {
  const queues = useManagedQueues(membership.organizationId);
  return (
    <section className="space-y-3" aria-labelledby={`org-${membership.organizationId}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={`org-${membership.organizationId}`} className="text-lg font-semibold text-slate-900">{membership.organizationName}</h2>
        <Link to={`/admin/organizations/${membership.organizationId}`} className="inline-flex items-center gap-1 text-sm font-medium text-indigo-600 hover:underline">
          <Settings className="size-4" aria-hidden /> {membership.role === 'ADMIN' ? 'Manage organization' : 'Organization overview'}
        </Link>
      </div>
      {queues.isPending && <Spinner />}
      {queues.isError && <ErrorState error={queues.error} onRetry={() => queues.refetch()} />}
      {queues.data?.length === 0 && <EmptyState title="No queues yet">An administrator can create queues.</EmptyState>}
      <div className="grid gap-3 md:grid-cols-2">{queues.data?.map((q) => <QueueTile key={q.id} queue={q} />)}</div>
    </section>
  );
}

export function StaffHomePage() {
  const me = useMe();
  const memberships = staffMemberships(me.data);
  return (
    <div className="space-y-8">
      <PageHeader eyebrow={<span className="inline-flex items-center gap-1"><LayoutDashboard className="size-4" aria-hidden /> Staff</span>}
                  title="Your queues" subtitle="Open a queue’s live board to call, serve and manage customers." />
      {memberships.map((m) => <OrganizationQueues key={m.organizationId} membership={m} />)}
    </div>
  );
}
