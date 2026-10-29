import { Link } from 'react-router';
import { ShieldAlert } from 'lucide-react';

export function AccessDeniedPage() {
  return (
    <div className="mx-auto max-w-lg py-16 text-center">
      <ShieldAlert className="mx-auto size-10 text-amber-500" aria-hidden />
      <h1 className="mt-4 text-2xl font-semibold text-slate-900">Access denied</h1>
      <p className="mt-2 text-slate-600">
        Your account does not have the role needed for this page. Staff and administrator access is granted
        by an administrator of the organization.
      </p>
      <Link to="/" className="mt-6 inline-block rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">
        Go home
      </Link>
    </div>
  );
}
