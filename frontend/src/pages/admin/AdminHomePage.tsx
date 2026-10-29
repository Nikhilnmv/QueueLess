import { Link } from 'react-router';
import { Building2, ChevronRight } from 'lucide-react';
import { useMe } from '../../api/queries';
import { adminMemberships } from '../../auth/roles';
import { Card, PageHeader } from '../../components/ui';

export function AdminHomePage() {
  const me = useMe();
  const organizations = adminMemberships(me.data);
  return (
    <div>
      <PageHeader title="Administration" subtitle="Organizations you administer. You can only manage these; other organizations’ data is never shown." />
      <div className="grid gap-3 md:grid-cols-2">
        {organizations.map((m) => (
          <Link key={m.organizationId} to={`/admin/organizations/${m.organizationId}`} className="group">
            <Card className="flex items-center justify-between transition-shadow group-hover:shadow-md">
              <div className="flex items-center gap-3">
                <Building2 className="size-6 text-indigo-600" aria-hidden />
                <div>
                  <div className="font-medium text-slate-900 group-hover:text-indigo-700">{m.organizationName}</div>
                  <div className="text-sm text-slate-500">/{m.organizationSlug}</div>
                </div>
              </div>
              <ChevronRight className="size-5 text-slate-400" aria-hidden />
            </Card>
          </Link>
        ))}
      </div>
    </div>
  );
}
