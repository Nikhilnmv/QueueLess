import { Link, isRouteErrorResponse, useRouteError } from 'react-router';
import { MapPin } from 'lucide-react';

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-lg py-16 text-center">
      <MapPin className="mx-auto size-10 text-slate-400" aria-hidden />
      <h1 className="mt-4 text-2xl font-semibold text-slate-900">Page not found</h1>
      <p className="mt-2 text-slate-600">
        The page you are looking for does not exist, or the link has expired.
      </p>
      <div className="mt-6 flex justify-center gap-3">
        <Link to="/" className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">Go home</Link>
        <Link to="/organizations" className="rounded-lg px-4 py-2 text-sm font-medium text-slate-700 ring-1 ring-slate-300 hover:bg-slate-50">
          Find a service
        </Link>
      </div>
    </div>
  );
}

/** Rendered when a page crashes while rendering, instead of a blank screen. */
export function RouteErrorPage() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : 'An unexpected error occurred.';
  return (
    <div className="mx-auto max-w-lg py-16 text-center">
      <h1 className="text-2xl font-semibold text-slate-900">Something went wrong</h1>
      <p className="mt-2 text-slate-600">{message}</p>
      <a href="/" className="mt-6 inline-block rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white">Reload QueueLess</a>
    </div>
  );
}
