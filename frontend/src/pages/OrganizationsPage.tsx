import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useOrganizations } from '../api/queries';
import { Empty, ErrorNotice, Input, Loading, PageTitle, table, td, th } from '../components/ui';

export function OrganizationsPage() {
  const [text, setText] = useState('');
  const [search, setSearch] = useState('');
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(text.trim()), 250); // wait for typing to pause
    return () => window.clearTimeout(timer);
  }, [text]);
  const organizations = useOrganizations(search);

  return (
    <div>
      <PageTitle title="Find a service" description="Pick an organization to see its counters, waiting times and free slots." />
      <div className="mb-5 max-w-sm">
        <label htmlFor="search" className="sr-only">Search by name</label>
        <Input id="search" placeholder="Search by name" value={text} onChange={(e) => setText(e.target.value)} />
      </div>
      {organizations.isPending && <Loading />}
      {organizations.isError && <ErrorNotice error={organizations.error} onRetry={() => organizations.refetch()} />}
      {organizations.data && organizations.data.length === 0 && (
        <Empty>{search ? `No organization matches "${search}".` : 'No organization has been added yet.'}</Empty>
      )}
      {organizations.data && organizations.data.length > 0 && (
        <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
          <table className={table}>
            <thead>
              <tr><th className={th}>Organization</th><th className={th}>About</th><th className={th}>Time zone</th></tr>
            </thead>
            <tbody>
              {organizations.data.map((organization) => (
                <tr key={organization.id} className="hover:bg-stone-50">
                  <td className={td}>
                    <Link to={`/organizations/${organization.slug}`} className="font-medium text-pine-700 hover:underline">
                      {organization.name}
                    </Link>
                  </td>
                  <td className={`${td} text-stone-600`}>{organization.description}</td>
                  <td className={`${td} whitespace-nowrap text-stone-500`}>{organization.timezone}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
