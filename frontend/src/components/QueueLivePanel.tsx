import type { QueueLive } from '../api/types';
import { minutesLabel } from '../lib/format';
import { Stat } from './ui';

export function estimateBasisText(basis: QueueLive['estimateBasis'], samples: number): string {
  return basis === 'HISTORY'
    ? `Based on the last ${samples} completed services`
    : 'Based on the queue’s typical service time (not enough history yet)';
}

/** Public live numbers for a queue; every value comes from GET /api/queues/{id}/live. */
export function QueueLivePanel({ live }: { live: QueueLive }) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
      <Stat label="Now serving" value={live.nowServing?.displayToken ?? '—'}
            hint={live.nowServing ? (live.nowServing.status === 'SERVING' ? 'At the counter' : 'Called') : 'Counter is free'} />
      <Stat label="Waiting" value={live.waitingCount} hint={live.waitingCount === 1 ? 'person in line' : 'people in line'} />
      <Stat label="Wait if you join now" value={minutesLabel(live.estimatedWaitMinutesForNewTicket)} hint="Estimate, not a guarantee" />
      <Stat label="Average service" value={`${live.averageServiceMinutes} min`} hint={estimateBasisText(live.estimateBasis, live.estimateSamples)} />
    </div>
  );
}
