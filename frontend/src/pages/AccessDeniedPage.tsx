import { Link } from 'react-router';

export function AccessDeniedPage() {
  return (
    <div className="mx-auto max-w-lg py-16">
      <p className="font-mono text-sm text-stone-500">403</p>
      <h1 className="mt-1 text-2xl font-semibold text-stone-900">Access denied</h1>
      <p className="mt-2 text-stone-600">
        Your account does not have the role this page needs. An administrator of the organization can give you
        staff or admin access.
      </p>
      <p className="mt-6 text-sm"><Link to="/" className="text-pine-700 underline underline-offset-2">Go home</Link></p>
    </div>
  );
}
