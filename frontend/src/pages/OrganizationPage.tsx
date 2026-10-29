import { Link, useParams } from 'react-router';
import { CalendarClock, ChevronRight, Clock, Users } from 'lucide-react';
import { useOrganization, useOrganizationQueues, useQueueLive } from '../api/queries';
import type { Queue } from '../api/types';
import { QueueStatusBadge } from '../components/StatusBadges';
import { Badge, Card, EmptyState, ErrorState, PageHeader, Spinner } from '../components/ui';
import { formatLocalTime, minutesLabel } from '../lib/format';

function QueueCard({ queue }: { queue: Queue }) {
  const live = useQueueLive(queue.id);
  return (
    <Link to={`/queues/${queue.id}`} className="group">
      <Card className="flex h-full flex-col gap-3 transition-shadow group-hover:shadow-md">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="font-semibold text-slate-900 group-hover:text-indigo-700">{queue.name}</h2>
            {queue.description && <p className="mt-0.5 text-sm text-slate-600">{queue.description}</p>}
          </div>
          <QueueStatusBadge status={queue.status} />
        </div>
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-600">
          <span className="inline-flex items-center gap-1"><Clock className="size-4" aria-hidden />
            {formatLocalTime(queue.openingTime)}–{formatLocalTime(queue.closingTime)}</span>
          {live.data && (
            <span className="inline-flex items-center gap-1"><Users className="size-4" aria-hidden />
              {live.data.waitingCount} waiting · {minutesLabel(live.data.estimatedWaitMinutesForNewTicket)}</span>
          )}
        </div>
        <div className="mt-auto flex items-center justify-between">
          {queue.bookingEnabled
            ? <Badge tone="indigo"><CalendarClock className="size-3" aria-hidden /> Appointments available</Badge>
            : <Badge>Walk-in only</Badge>}
          <ChevronRight className="size-5 text-slate-400" aria-hidden />
        </div>
      </Card>
    </Link>
  );
}

export function OrganizationPage() {
  const { slug = '' } = useParams();
  const organization = useOrganization(slug);
  const queues = useOrganizationQueues(slug);

  if (organization.isPending) return <Spinner />;
  if (organization.isError) return <ErrorState error={organization.error} onRetry={() => organization.refetch()} />;

  return (
    <div>
      <PageHeader
        eyebrow={<Link to="/organizations" className="hover:underline">All organizations</Link>}
        title={organization.data.name}
        subtitle={organization.data.description}
      />
      <p className="-mt-3 mb-6 text-sm text-slate-500">All times are in {organization.data.timezone}.</p>
      {queues.isPending && <Spinner label="Loading queues" />}
      {queues.isError && <ErrorState error={queues.error} onRetry={() => queues.refetch()} />}
      {queues.data?.length === 0 && <EmptyState title="No queues yet">This organization has not opened any queues.</EmptyState>}
      <div className="grid gap-4 md:grid-cols-2">
        {queues.data?.map((queue) => <QueueCard key={queue.id} queue={queue} />)}
      </div>
    </div>
  );
}
