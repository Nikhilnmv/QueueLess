import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../../api/client';
import { useSaveQueue } from '../../api/queries';
import type { Queue, QueueConfig } from '../../api/types';
import { Button, Dialog, Field, Input, Notice, TextArea } from '../../components/ui';
import { formatLocalTime } from '../../lib/format';

const emptyConfig: QueueConfig = {
  name: '', description: '', tokenPrefix: '', openingTime: '09:00', closingTime: '17:00',
  slotDurationMinutes: 15, capacityPerSlot: 2, defaultServiceMinutes: 10, bookingEnabled: true,
};

function toConfig(queue: Queue): QueueConfig {
  return {
    name: queue.name, description: queue.description ?? '', tokenPrefix: queue.tokenPrefix,
    openingTime: formatLocalTime(queue.openingTime), closingTime: formatLocalTime(queue.closingTime),
    slotDurationMinutes: queue.slotDurationMinutes, capacityPerSlot: queue.capacityPerSlot,
    defaultServiceMinutes: queue.defaultServiceMinutes, bookingEnabled: queue.bookingEnabled,
  };
}

/** Create or edit a queue. Status, version and organization are not editable here (server-controlled). */
export function QueueFormDialog({ orgId, queue, open, onClose }: { orgId: string; queue: Queue | null; open: boolean; onClose: () => void }) {
  const [config, setConfig] = useState<QueueConfig>(emptyConfig);
  const save = useSaveQueue(orgId);

  useEffect(() => {
    if (open) {
      setConfig(queue ? toConfig(queue) : emptyConfig);
      save.reset();
    }
    // Reset only when the dialog opens or switches queue, not on every render of `save`.
  }, [open, queue?.id]);

  const fieldErrors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const set = <K extends keyof QueueConfig>(key: K, value: QueueConfig[K]) => setConfig((c) => ({ ...c, [key]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate({ queueId: queue?.id, config: { ...config, description: config.description?.trim() || null } }, { onSuccess: onClose });
  };

  return (
    <Dialog open={open} onClose={onClose} title={queue ? `Edit ${queue.name}` : 'New queue'}>
      <form onSubmit={submit} className="space-y-3">
        {save.isError && Object.keys(fieldErrors).length === 0 && <Notice tone="error">{errorMessage(save.error)}</Notice>}
        <Field label="Name" htmlFor="q-name" error={fieldErrors.name}>
          <Input id="q-name" required maxLength={100} value={config.name} onChange={(e) => set('name', e.target.value)} />
        </Field>
        <Field label="Description" htmlFor="q-description" error={fieldErrors.description}>
          <TextArea id="q-description" rows={2} maxLength={500} value={config.description ?? ''} onChange={(e) => set('description', e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Token prefix" htmlFor="q-prefix" error={fieldErrors.tokenPrefix} hint="1 to 5 letters; G gives G-001, G-002">
            <Input id="q-prefix" required maxLength={5} value={config.tokenPrefix} onChange={(e) => set('tokenPrefix', e.target.value.toUpperCase())} />
          </Field>
          <Field label="Typical service (minutes)" htmlFor="q-service" error={fieldErrors.defaultServiceMinutes}
                 hint="Used for estimates until there is history">
            <Input id="q-service" type="number" min={1} max={240} value={config.defaultServiceMinutes}
                   onChange={(e) => set('defaultServiceMinutes', Number(e.target.value))} />
          </Field>
          <Field label="Opens" htmlFor="q-open" error={fieldErrors.openingTime}>
            <Input id="q-open" type="time" required value={config.openingTime} onChange={(e) => set('openingTime', e.target.value)} />
          </Field>
          <Field label="Closes" htmlFor="q-close" error={fieldErrors.closingTime}>
            <Input id="q-close" type="time" required value={config.closingTime} onChange={(e) => set('closingTime', e.target.value)} />
          </Field>
          <Field label="Slot length (minutes)" htmlFor="q-slot" error={fieldErrors.slotDurationMinutes}>
            <Input id="q-slot" type="number" min={5} max={240} value={config.slotDurationMinutes}
                   onChange={(e) => set('slotDurationMinutes', Number(e.target.value))} />
          </Field>
          <Field label="Bookings per slot" htmlFor="q-capacity" error={fieldErrors.capacityPerSlot}>
            <Input id="q-capacity" type="number" min={1} max={100} value={config.capacityPerSlot}
                   onChange={(e) => set('capacityPerSlot', Number(e.target.value))} />
          </Field>
        </div>
        <label className="flex items-center gap-2 text-sm text-stone-700">
          <input type="checkbox" checked={config.bookingEnabled} onChange={(e) => set('bookingEnabled', e.target.checked)}
                 className="size-4 accent-pine-700" />
          Accept appointment bookings
        </label>
        {queue && <p className="text-xs text-stone-500">Existing bookings keep their times if the schedule changes.</p>}
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button type="submit" loading={save.isPending}>{queue ? 'Save changes' : 'Create queue'}</Button>
        </div>
      </form>
    </Dialog>
  );
}
