import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useMe } from '../api/queries';
import type { CurrentUser } from '../api/types';
import { ErrorState, Spinner } from '../components/ui';
import { AccessDeniedPage } from '../pages/AccessDeniedPage';

/** Renders children only for signed-in users; otherwise redirects to login and comes back afterwards. */
export function RequireAuth({ children, allow }: { children: ReactNode; allow?: (user: CurrentUser) => boolean }) {
  const me = useMe();
  const location = useLocation();
  if (me.isPending) return <Spinner />;
  if (me.isError) return <ErrorState error={me.error} onRetry={() => me.refetch()} />;
  if (!me.data) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  if (allow && !allow(me.data)) return <AccessDeniedPage />;
  return <>{children}</>;
}
