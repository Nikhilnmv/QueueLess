import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { Pencil, Plus, Trash2, UserPlus } from 'lucide-react';
import { errorMessage } from '../../api/client';
import { useManagedQueues, useManagement, useMe, useMemberActions, useMembers, useUpdateOrganization } from '../../api/queries';
import type { Member, MembershipRole, Queue } from '../../api/types';
import { QueueStatusBadge } from '../../components/StatusBadges';
import { Alert, Badge, Button, Card, ConfirmDialog, ErrorState, Field, Input, PageHeader, Select, Spinner, Stat, TextArea, cx } from '../../components/ui';
import { formatLocalTime } from '../../lib/format';
import { QueueFormDialog } from './QueueFormDialog';

type Tab = 'queues' | 'members' | 'profile';

function QueuesTab({ orgId, canEdit }: { orgId: string; canEdit: boolean }) {
  const queues = useManagedQueues(orgId);
  const [editing, setEditing] = useState<Queue | null>(null);
  const [creating, setCreating] = useState(false);
  if (queues.isPending) return <Spinner />;
  if (queues.isError) return <ErrorState error={queues.error} onRetry={() => queues.refetch()} />;
  return (
    <div className="space-y-3">
      {canEdit && <div className="flex justify-end"><Button icon={<Plus className="size-4" aria-hidden />} onClick={() => setCreating(true)}>New queue</Button></div>}
      {queues.data.map((q) => (
        <Card key={q.id} className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium text-slate-900">{q.name}</span>
              <QueueStatusBadge status={q.status} />
              {q.bookingEnabled ? <Badge tone="indigo">Bookings on</Badge> : <Badge>Walk-in only</Badge>}
            </div>
            <div className="mt-1 text-sm text-slate-600">
              Prefix {q.tokenPrefix} · {formatLocalTime(q.openingTime)}–{formatLocalTime(q.closingTime)} · {q.slotDurationMinutes}-min slots ·
              {' '}{q.capacityPerSlot} per slot · ~{q.defaultServiceMinutes} min service
            </div>
          </div>
          <div className="flex gap-2">
            <Link to={`/staff/queues/${q.id}`} className="rounded-lg px-3 py-1.5 text-sm font-medium text-indigo-600 ring-1 ring-indigo-200 hover:bg-indigo-50">Live board</Link>
            {canEdit && <Button size="sm" variant="secondary" icon={<Pencil className="size-4" aria-hidden />} onClick={() => setEditing(q)}>Edit</Button>}
          </div>
        </Card>
      ))}
      <QueueFormDialog orgId={orgId} queue={editing} open={creating || !!editing} onClose={() => { setCreating(false); setEditing(null); }} />
    </div>
  );
}

function MembersTab({ orgId }: { orgId: string }) {
  const me = useMe();
  const members = useMembers(orgId);
  const actions = useMemberActions(orgId);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<MembershipRole>('STAFF');
  const [removing, setRemoving] = useState<Member | null>(null);
  const error = [actions.add, actions.changeRole, actions.remove].find((m) => m.isError)?.error;

  const add = (event: FormEvent) => {
    event.preventDefault();
    actions.changeRole.reset(); actions.remove.reset();
    actions.add.mutate({ email: email.trim(), role }, { onSuccess: () => setEmail('') });
  };

  if (members.isPending) return <Spinner />;
  if (members.isError) return <ErrorState error={members.error} onRetry={() => members.refetch()} />;
  return (
    <div className="space-y-4">
      <Card>
        <h3 className="font-medium text-slate-900">Add a member</h3>
        <p className="mt-1 text-sm text-slate-600">The person must already have a QueueLess account. Staff can operate queues; admins can also configure queues and members.</p>
        <form onSubmit={add} className="mt-3 flex flex-col gap-2 sm:flex-row">
          <label htmlFor="member-email" className="sr-only">Email</label>
          <Input id="member-email" type="email" placeholder="person@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
          <label htmlFor="member-role" className="sr-only">Role</label>
          <Select id="member-role" value={role} className="sm:w-36" onChange={(e) => setRole(e.target.value as MembershipRole)}>
            <option value="STAFF">Staff</option><option value="ADMIN">Admin</option><option value="CUSTOMER">Customer</option>
          </Select>
          <Button type="submit" loading={actions.add.isPending} disabled={!email.trim()} icon={<UserPlus className="size-4" aria-hidden />}>Add</Button>
        </form>
      </Card>
      {error && <Alert tone="error">{errorMessage(error)}</Alert>}
      <Card className="overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr><th className="px-5 py-2">Name</th><th className="px-3 py-2">Email</th><th className="px-3 py-2">Role</th><th className="px-3 py-2"><span className="sr-only">Actions</span></th></tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {members.data.map((m) => (
              <tr key={m.membershipId}>
                <td className="px-5 py-2 font-medium text-slate-900">{m.fullName}{m.userId === me.data?.id && <span className="ml-2 text-xs text-slate-500">(you)</span>}</td>
                <td className="px-3 py-2 text-slate-600">{m.email}</td>
                <td className="px-3 py-2">
                  <label className="sr-only" htmlFor={`role-${m.membershipId}`}>Role of {m.fullName}</label>
                  <Select id={`role-${m.membershipId}`} value={m.role} className="w-32 py-1" disabled={actions.changeRole.isPending}
                          onChange={(e) => { actions.add.reset(); actions.remove.reset(); actions.changeRole.mutate({ membershipId: m.membershipId, role: e.target.value as MembershipRole }); }}>
                    <option value="CUSTOMER">Customer</option><option value="STAFF">Staff</option><option value="ADMIN">Admin</option>
                  </Select>
                </td>
                <td className="px-3 py-2 text-right">
                  <Button size="sm" variant="ghost" icon={<Trash2 className="size-4" aria-hidden />} onClick={() => setRemoving(m)}>Remove</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      <ConfirmDialog open={!!removing} title={`Remove ${removing?.fullName ?? ''}?`} confirmLabel="Remove member" loading={actions.remove.isPending}
                     onCancel={() => setRemoving(null)}
                     onConfirm={() => removing && actions.remove.mutate(removing.membershipId, { onSettled: () => setRemoving(null) })}>
        They lose their role in this organization immediately. Their own bookings are not affected.
      </ConfirmDialog>
    </div>
  );
}

function ProfileTab({ orgId, name, description }: { orgId: string; name: string; description: string | null }) {
  const update = useUpdateOrganization(orgId);
  const [form, setForm] = useState({ name, description: description ?? '' });
  useEffect(() => setForm({ name, description: description ?? '' }), [name, description]);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    update.mutate({ name: form.name.trim(), description: form.description.trim() || null });
  };
  return (
    <Card className="max-w-xl">
      <form onSubmit={submit} className="space-y-3">
        {update.isError && <Alert tone="error">{errorMessage(update.error)}</Alert>}
        {update.isSuccess && <Alert tone="success">Saved.</Alert>}
        <Field label="Name" htmlFor="org-name"><Input id="org-name" required maxLength={120} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        <Field label="Description" htmlFor="org-description"><TextArea id="org-description" rows={3} maxLength={500} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
        <p className="text-xs text-slate-500">The slug and time zone are fixed after creation, so existing bookings never shift.</p>
        <Button type="submit" loading={update.isPending}>Save</Button>
      </form>
    </Card>
  );
}

export function AdminOrganizationPage() {
  const { orgId = '' } = useParams();
  const management = useManagement(orgId);
  const [tab, setTab] = useState<Tab>('queues');

  if (management.isPending) return <Spinner />;
  if (management.isError) return <ErrorState error={management.error} onRetry={() => management.refetch()} />;
  const { organization, yourRole, members } = management.data;
  const isAdmin = yourRole === 'ADMIN';
  const tabs: { id: Tab; label: string }[] = isAdmin
    ? [{ id: 'queues', label: 'Queues' }, { id: 'members', label: 'Members' }, { id: 'profile', label: 'Profile' }]
    : [{ id: 'queues', label: 'Queues' }];

  return (
    <div className="space-y-6">
      <PageHeader eyebrow={<Link to={isAdmin ? '/admin' : '/staff'} className="hover:underline">{isAdmin ? 'Administration' : 'Staff'}</Link>}
                  title={organization.name} subtitle={`/${organization.slug} · ${organization.timezone} · your role: ${yourRole.toLowerCase()}`} />
      <div className="grid grid-cols-3 gap-3 sm:max-w-md">
        <Stat label="Admins" value={members.admins} />
        <Stat label="Staff" value={members.staff} />
        <Stat label="Customers" value={members.customers} />
      </div>
      <div className="flex gap-1 border-b border-slate-200" role="tablist">
        {tabs.map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}
                  className={cx('-mb-px border-b-2 px-4 py-2 text-sm font-medium',
                    tab === t.id ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-600 hover:text-slate-900')}>
            {t.label}
          </button>
        ))}
      </div>
      {tab === 'queues' && <QueuesTab orgId={orgId} canEdit={isAdmin} />}
      {tab === 'members' && isAdmin && <MembersTab orgId={orgId} />}
      {tab === 'profile' && isAdmin && <ProfileTab orgId={orgId} name={organization.name} description={organization.description} />}
    </div>
  );
}
