import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { Building2, ChevronRight, Search } from 'lucide-react';
import { useOrganizations } from '../api/queries';
import { Card, EmptyState, ErrorState, Input, PageHeader, Spinner } from '../components/ui';

export function OrganizationsPage() {
  const [text, setText] = useState('');
  const [search, setSearch] = useState('');
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(text.trim()), 250); // debounce typing
    return () => window.clearTimeout(timer);
  }, [text]);
  const organizations = useOrganizations(search);

  return (
    <div>
      <PageHeader title="Find a service" subtitle="Choose an organization to see its queues, live waiting times and appointment slots." />
      <div className="relative mb-6 max-w-md">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" aria-hidden />
        <label htmlFor="search" className="sr-only">Search organizations</label>
        <Input id="search" className="pl-9" placeholder="Search by name…" value={text} onChange={(e) => setText(e.target.value)} />
      </div>
      {organizations.isPending && <Spinner />}
      {organizations.isError && <ErrorState error={organizations.error} onRetry={() => organizations.refetch()} />}
      {organizations.data && organizations.data.length === 0 && (
        <EmptyState icon={<Building2 className="size-8" />} title="No organizations found">
          {search ? `Nothing matches “${search}”.` : 'No organization has been set up yet.'}
        </EmptyState>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        {organizations.data?.map((organization) => (
          <Link key={organization.id} to={`/organizations/${organization.slug}`} className="group">
            <Card className="flex h-full items-start justify-between gap-4 transition-shadow group-hover:shadow-md">
              <div>
                <div className="flex items-center gap-2 font-medium text-slate-900 group-hover:text-indigo-700">
                  <Building2 className="size-4 text-indigo-600" aria-hidden />
                  {organization.name}
                </div>
                {organization.description && <p className="mt-1 text-sm text-slate-600">{organization.description}</p>}
                <p className="mt-2 text-xs text-slate-500">Time zone: {organization.timezone}</p>
              </div>
              <ChevronRight className="mt-1 size-5 shrink-0 text-slate-400" aria-hidden />
            </Card>
          </Link>
        ))}
      </div>
    </div>
  );
}
