import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Building2 } from 'lucide-react';
import { ApiError, errorMessage } from '../../api/client';
import { useCreateOrganization } from '../../api/queries';
import { Alert, Button, Card, Field, Input, PageHeader, Select, TextArea } from '../../components/ui';

const zones = ['Asia/Kolkata', 'Asia/Singapore', 'Asia/Dubai', 'Europe/London', 'Europe/Berlin', 'America/New_York', 'America/Los_Angeles', 'UTC'];

/** Platform operators provision organizations; they do not get access to an organization's data. */
export function PlatformPage() {
  const create = useCreateOrganization();
  const [form, setForm] = useState({ name: '', slug: '', description: '', timezone: 'Asia/Kolkata', adminEmail: '' });
  const fieldErrors = create.error instanceof ApiError ? create.error.fieldErrors : {};
  const set = (key: keyof typeof form) => (value: string) => setForm((f) => ({ ...f, [key]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate({ ...form, name: form.name.trim(), slug: form.slug.trim(), adminEmail: form.adminEmail.trim() },
      { onSuccess: () => setForm({ name: '', slug: '', description: '', timezone: form.timezone, adminEmail: '' }) });
  };

  return (
    <div className="max-w-2xl">
      <PageHeader eyebrow="Platform" title="Provision an organization"
                  subtitle="Creates a tenant and makes an existing user its first administrator." />
      {create.isSuccess && (
        <div className="mb-4">
          <Alert tone="success" title="Organization created">
            <Link to={`/organizations/${create.data.slug}`} className="font-medium underline">{create.data.name}</Link> is live.
            Its administrator can now sign in and add queues and staff.
          </Alert>
        </div>
      )}
      <Card>
        <form onSubmit={submit} className="space-y-3">
          {create.isError && Object.keys(fieldErrors).length === 0 && <Alert tone="error">{errorMessage(create.error)}</Alert>}
          <Field label="Name" htmlFor="p-name" error={fieldErrors.name}>
            <Input id="p-name" required value={form.name} onChange={(e) => set('name')(e.target.value)} />
          </Field>
          <Field label="Slug (URL name)" htmlFor="p-slug" error={fieldErrors.slug} hint="Lower-case letters, digits and hyphens, e.g. sunrise-diagnostics">
            <Input id="p-slug" required value={form.slug} onChange={(e) => set('slug')(e.target.value.toLowerCase())} />
          </Field>
          <Field label="Description" htmlFor="p-description" error={fieldErrors.description}>
            <TextArea id="p-description" rows={2} value={form.description} onChange={(e) => set('description')(e.target.value)} />
          </Field>
          <Field label="Time zone" htmlFor="p-zone" error={fieldErrors.timezone} hint="Fixed after creation">
            <Select id="p-zone" value={form.timezone} onChange={(e) => set('timezone')(e.target.value)}>
              {zones.map((z) => <option key={z} value={z}>{z}</option>)}
            </Select>
          </Field>
          <Field label="First administrator’s email" htmlFor="p-admin" error={fieldErrors.adminEmail} hint="Must be a registered user">
            <Input id="p-admin" type="email" required value={form.adminEmail} onChange={(e) => set('adminEmail')(e.target.value)} />
          </Field>
          <Button type="submit" loading={create.isPending} icon={<Building2 className="size-4" aria-hidden />}>Create organization</Button>
        </form>
      </Card>
    </div>
  );
}
