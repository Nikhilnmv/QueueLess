import { Link } from 'react-router';
import { useMe, useOrganizations } from '../api/queries';
import { table, td, th } from '../components/ui';

export function LandingPage() {
  const me = useMe();
  const organizations = useOrganizations('');

  return (
    <div className="space-y-12">
      <section className="max-w-2xl pt-4">
        <h1 className="text-3xl font-semibold leading-tight text-stone-900">Book a time, or join the queue from your phone</h1>
        <p className="mt-4 text-stone-600">
          QueueLess runs the queues of clinics, college offices and service counters. Book an appointment slot or
          take a token for today, then follow your place in line and the estimated wait. You get a notification when
          your turn is close and when you are called.
        </p>
        <div className="mt-6 flex flex-wrap items-center gap-4">
          <Link to="/organizations" className="rounded bg-pine-700 px-4 py-2 font-medium text-white hover:bg-pine-800">
            Find a service
          </Link>
          {me.data
            ? <Link to="/dashboard" className="text-pine-700 underline underline-offset-2">Go to my visits</Link>
            : <Link to="/register" className="text-pine-700 underline underline-offset-2">Create an account</Link>}
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-lg font-semibold text-stone-900">Services on QueueLess</h2>
        <div className="overflow-x-auto rounded-md border border-stone-200 bg-white">
          <table className={table}>
            <thead>
              <tr><th className={th}>Organization</th><th className={th}>About</th><th className={th}>Time zone</th></tr>
            </thead>
            <tbody>
              {organizations.data?.map((organization) => (
                <tr key={organization.id}>
                  <td className={td}>
                    <Link to={`/organizations/${organization.slug}`} className="font-medium text-pine-700 hover:underline">
                      {organization.name}
                    </Link>
                  </td>
                  <td className={`${td} text-stone-600`}>{organization.description}</td>
                  <td className={`${td} whitespace-nowrap text-stone-500`}>{organization.timezone}</td>
                </tr>
              ))}
              {organizations.isPending && <tr><td className={td} colSpan={3}>Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="grid gap-8 md:grid-cols-2">
        <div>
          <h2 className="mb-3 text-lg font-semibold text-stone-900">For visitors</h2>
          <ol className="list-decimal space-y-2 pl-5 text-stone-700">
            <li>Choose the organization and the counter you need.</li>
            <li>Book a free slot, or take a token if you are already there.</li>
            <li>When you arrive for a booking, check in. Your place in line counts from check-in.</li>
            <li>Watch your position on the ticket page. It updates by itself.</li>
          </ol>
        </div>
        <div>
          <h2 className="mb-3 text-lg font-semibold text-stone-900">For staff and administrators</h2>
          <ol className="list-decimal space-y-2 pl-5 text-stone-700">
            <li>Open the live board of your counter.</li>
            <li>Press Call next, then mark the visit as started and completed.</li>
            <li>Add walk-in visitors who have no account, and raise priority when needed.</li>
            <li>Administrators set opening hours, slot length, capacity and staff accounts.</li>
          </ol>
        </div>
      </section>
    </div>
  );
}
