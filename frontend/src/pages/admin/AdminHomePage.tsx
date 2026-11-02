import { Link } from 'react-router';
import { useMe } from '../../api/queries';
import { adminMemberships } from '../../auth/roles';
import { Empty, PageTitle, table, td, th } from '../../components/ui';

export function AdminHomePage() {
  const me = useMe();
  const organizations = adminMemberships(me.data);
  return (
    <div>
      <PageTitle title="Administration"
                 description="The organizations you administer. Data from other organizations is never shown here." />
      {organizations.length === 0 ? (
        <Empty>You do not administer any organization.</Empty>
      ) : (
        <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
          <table className={table}>
            <thead><tr><th className={th}>Organization</th><th className={th}>Public page</th></tr></thead>
            <tbody>
              {organizations.map((m) => (
                <tr key={m.organizationId} className="hover:bg-stone-50">
                  <td className={td}>
                    <Link to={`/admin/organizations/${m.organizationId}`} className="font-medium text-pine-700 hover:underline">
                      {m.organizationName}
                    </Link>
                  </td>
                  <td className={td}>
                    <Link to={`/organizations/${m.organizationSlug}`} className="text-stone-600 hover:underline">
                      /organizations/{m.organizationSlug}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
