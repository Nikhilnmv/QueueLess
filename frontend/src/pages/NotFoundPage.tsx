import { Link, isRouteErrorResponse, useRouteError } from 'react-router';

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-lg py-16">
      <p className="font-mono text-sm text-stone-500">404</p>
      <h1 className="mt-1 text-2xl font-semibold text-stone-900">Page not found</h1>
      <p className="mt-2 text-stone-600">This page does not exist, or the link is out of date.</p>
      <p className="mt-6 flex gap-5 text-sm">
        <Link to="/" className="text-pine-700 underline underline-offset-2">Home</Link>
        <Link to="/organizations" className="text-pine-700 underline underline-offset-2">Find a service</Link>
      </p>
    </div>
  );
}

/** Rendered when a page crashes while rendering, instead of a blank screen. */
export function RouteErrorPage() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : 'The page failed to load.';
  return (
    <div className="mx-auto max-w-lg px-4 py-16">
      <h1 className="text-2xl font-semibold text-stone-900">Something went wrong</h1>
      <p className="mt-2 text-stone-600">{message}</p>
      <a href="/" className="mt-6 inline-block rounded bg-pine-700 px-3.5 py-2 text-sm font-medium text-white hover:bg-pine-800">
        Reload QueueLess
      </a>
    </div>
  );
}
