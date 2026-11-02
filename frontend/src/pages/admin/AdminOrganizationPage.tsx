import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { errorMessage } from '../../api/client';
import { useManagedQueues, useManagement, useMe, useMemberActions, useMembers, useUpdateOrganization } from '../../api/queries';
import type { Member, MembershipRole, Queue } from '../../api/types';
import { QueueStatusBadge } from '../../components/StatusBadges';
import { Button, ConfirmDialog, Empty, ErrorNotice, Field, Input, Loading, Notice, PageTitle, Panel, Select, TextArea, cx, table, td,
  th } from '../../components/ui';
import { formatLocalTime } from '../../lib/format';
import { QueueFormDialog } from './QueueFormDialog';

type Tab = 'queues' | 'members' | 'profile';

function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

function QueuesTab({ orgId, canEdit }: { orgId: string; canEdit: boolean }) {
  const queues = useManagedQueues(orgId);
  const [editing, setEditing] = useState<Queue | null>(null);
  const [creating, setCreating] = useState(false);
  if (queues.isPending) return <Loading />;
  if (queues.isError) return <ErrorNotice error={queues.error} onRetry={() => queues.refetch()} />;
  return (
    <div className="space-y-3">
      {canEdit && <div className="flex justify-end"><Button onClick={() => setCreating(true)}>New queue</Button></div>}
      {queues.data.length === 0 ? (
        <Empty>No queues yet.</Empty>
      ) : (
        <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
          <table className={table}>
            <thead>
              <tr>
                <th className={th}>Queue</th><th className={th}>Status</th><th className={th}>Prefix</th><th className={th}>Hours</th>
                <th className={th}>Appointments</th><th className={th}>Typical service</th>
                <th className={th}><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {queues.data.map((q) => (
                <tr key={q.id} className="hover:bg-stone-50">
                  <td className={cx(td, 'font-medium text-stone-900')}>{q.name}</td>
                  <td className={td}><QueueStatusBadge status={q.status} /></td>
                  <td className={cx(td, 'font-mono')}>{q.tokenPrefix}</td>
                  <td className={cx(td, 'tabular whitespace-nowrap')}>{formatLocalTime(q.openingTime)} to {formatLocalTime(q.closingTime)}</td>
                  <td className={td}>{q.bookingEnabled ? `${q.slotDurationMinutes}-min slots, ${q.capacityPerSlot} per slot` : 'Walk-in only'}</td>
                  <td className={cx(td, 'tabular')}>{q.defaultServiceMinutes} min</td>
                  <td className={cx(td, 'whitespace-nowrap text-right')}>
                    <Link to={`/staff/queues/${q.id}`} aria-label={`Board for ${q.name}`} className="text-pine-700 hover:underline">Board</Link>
                    {canEdit && (
                      <button type="button" aria-label={`Edit ${q.name}`} onClick={() => setEditing(q)}
                              className="ml-4 text-pine-700 hover:underline">Edit</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
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

  if (members.isPending) return <Loading />;
  if (members.isError) return <ErrorNotice error={members.error} onRetry={() => members.refetch()} />;
  return (
    <div className="space-y-4">
      <Panel title="Add a member">
        <p className="text-sm text-stone-600">
          The person needs a QueueLess account first. Staff run queues; admins can also set up queues and members.
        </p>
        <form onSubmit={add} className="mt-3 flex flex-col gap-3 sm:flex-row sm:items-end">
          <div className="flex-1">
            <Field label="Email" htmlFor="member-email">
              <Input id="member-email" type="email" placeholder="name@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
            </Field>
          </div>
          <div className="sm:w-40">
            <Field label="Role" htmlFor="member-role">
              <Select id="member-role" value={role} onChange={(e) => setRole(e.target.value as MembershipRole)}>
                <option value="STAFF">Staff</option><option value="ADMIN">Admin</option><option value="CUSTOMER">Customer</option>
              </Select>
            </Field>
          </div>
          <Button type="submit" loading={actions.add.isPending} disabled={!email.trim()}>Add</Button>
        </form>
      </Panel>
      {error && <Notice tone="error">{errorMessage(error)}</Notice>}
      <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
        <table className={table}>
          <thead>
            <tr><th className={th}>Name</th><th className={th}>Email</th><th className={th}>Role</th><th className={th}><span className="sr-only">Actions</span></th></tr>
          </thead>
          <tbody>
            {members.data.map((m) => (
              <tr key={m.membershipId}>
                <td className={cx(td, 'font-medium text-stone-900')}>
                  {m.fullName}{m.userId === me.data?.id && <span className="ml-2 text-xs font-normal text-stone-500">(you)</span>}
                </td>
                <td className={cx(td, 'text-stone-600')}>{m.email}</td>
                <td className={td}>
                  <label className="sr-only" htmlFor={`role-${m.membershipId}`}>Role of {m.fullName}</label>
                  <Select id={`role-${m.membershipId}`} value={m.role} className="w-32 py-1!" disabled={actions.changeRole.isPending}
                          onChange={(e) => {
                            actions.add.reset(); actions.remove.reset();
                            actions.changeRole.mutate({ membershipId: m.membershipId, role: e.target.value as MembershipRole });
                          }}>
                    <option value="CUSTOMER">Customer</option><option value="STAFF">Staff</option><option value="ADMIN">Admin</option>
                  </Select>
                </td>
                <td className={cx(td, 'text-right')}>
                  <button type="button" onClick={() => setRemoving(m)} className="text-sm text-red-700 hover:underline">Remove</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ConfirmDialog open={!!removing} title={`Remove ${removing?.fullName ?? ''}?`} confirmLabel="Remove member" loading={actions.remove.isPending}
                     onCancel={() => setRemoving(null)}
                     onConfirm={() => removing && actions.remove.mutate(removing.membershipId, { onSettled: () => setRemoving(null) })}>
        They lose their role in this organization straight away. Their own bookings stay as they are.
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
    <Panel title="Public profile" className="max-w-xl">
      <form onSubmit={submit} className="space-y-3">
        {update.isError && <Notice tone="error">{errorMessage(update.error)}</Notice>}
        {update.isSuccess && <Notice tone="success">Saved.</Notice>}
        <Field label="Name" htmlFor="org-name">
          <Input id="org-name" required maxLength={120} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </Field>
        <Field label="Description" htmlFor="org-description">
          <TextArea id="org-description" rows={3} maxLength={500} value={form.description}
                    onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </Field>
        <p className="text-xs text-stone-500">The web address and the time zone cannot change after creation, so existing bookings never move.</p>
        <Button type="submit" loading={update.isPending}>Save</Button>
      </form>
    </Panel>
  );
}

export function AdminOrganizationPage() {
  const { orgId = '' } = useParams();
  const management = useManagement(orgId);
  const [tab, setTab] = useState<Tab>('queues');

  if (management.isPending) return <Loading />;
  if (management.isError) return <ErrorNotice error={management.error} onRetry={() => management.refetch()} />;
  const { organization, yourRole, members } = management.data;
  const isAdmin = yourRole === 'ADMIN';
  const tabs: { id: Tab; label: string }[] = isAdmin
    ? [{ id: 'queues', label: 'Queues' }, { id: 'members', label: 'Members' }, { id: 'profile', label: 'Profile' }]
    : [{ id: 'queues', label: 'Queues' }];

  return (
    <div className="space-y-6">
      <PageTitle
        back={<Link to={isAdmin ? '/admin' : '/staff'} className="text-pine-700 hover:underline">{isAdmin ? 'Administration' : 'Staff'}</Link>}
        title={organization.name}
        description={
          <>
            Time zone {organization.timezone} · your role: {yourRole.toLowerCase()} ·{' '}
            <Link to={`/organizations/${organization.slug}`} className="text-pine-700 hover:underline">public page</Link>
          </>
        }
        actions={
          <span className="text-sm text-stone-600">
            {count(members.admins, 'admin', 'admins')} · {members.staff} staff · {count(members.customers, 'customer', 'customers')}
          </span>
        }
      />
      <div className="flex gap-1 border-b border-stone-200" role="tablist">
        {tabs.map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}
                  className={cx('-mb-px border-b-2 px-3 py-2 text-sm',
                    tab === t.id ? 'border-pine-700 font-medium text-stone-900' : 'border-transparent text-stone-600 hover:text-stone-900')}>
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
