import { Link } from 'react-router';
import { Activity, BellRing, CalendarClock, ChevronRight, ListOrdered, ShieldCheck, Ticket } from 'lucide-react';
import { useMe, useOrganizations } from '../api/queries';
import { Card } from '../components/ui';

const features = [
  { icon: Ticket, title: 'Get a token from anywhere', text: 'Join a live queue as a walk-in or reserve a token with an appointment slot.' },
  { icon: Activity, title: 'Live position and wait', text: 'Your place in line and an estimated wait, recalculated on the server whenever the queue moves.' },
  { icon: BellRing, title: 'Told when it is your turn', text: 'In-app notifications when your turn approaches and when you are called. They are saved, so nothing is missed.' },
  { icon: CalendarClock, title: 'Appointments that respect capacity', text: 'Slots are generated from opening hours and can never be overbooked, even when many people book at once.' },
  { icon: ListOrdered, title: 'Fair, transparent ordering', text: 'Staff-controlled priority first, then check-in time. The policy is shown to staff on every board.' },
  { icon: ShieldCheck, title: 'Built for many organizations', text: 'Clinics, offices and help desks share the platform; each one only ever sees its own data.' },
];

export function LandingPage() {
  const me = useMe();
  const organizations = useOrganizations('');

  return (
    <div className="space-y-16">
      <section className="grid items-center gap-10 py-6 md:grid-cols-2">
        <div>
          <p className="mb-3 inline-flex rounded-full bg-indigo-50 px-3 py-1 text-sm font-medium text-indigo-700 ring-1 ring-indigo-200">
            Real-time queue and appointment management
          </p>
          <h1 className="text-4xl font-bold tracking-tight text-slate-900 sm:text-5xl">Wait less. Know exactly when it is your turn.</h1>
          <p className="mt-4 text-lg text-slate-600">
            QueueLess lets clinics, college offices, labs and help desks run their queues online. Customers book a slot
            or take a token, watch their live position, and get notified when they are called. Staff call the next
            person with one click.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link to="/organizations" className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-5 py-3 font-medium text-white shadow-sm hover:bg-indigo-700">
              Find a service <ChevronRight className="size-4" aria-hidden />
            </Link>
            {!me.data && (
              <Link to="/register" className="rounded-lg bg-white px-5 py-3 font-medium text-slate-700 shadow-sm ring-1 ring-slate-300 hover:bg-slate-50">
                Create a free account
              </Link>
            )}
            {me.data && (
              <Link to="/dashboard" className="rounded-lg bg-white px-5 py-3 font-medium text-slate-700 shadow-sm ring-1 ring-slate-300 hover:bg-slate-50">
                Go to my queue
              </Link>
            )}
          </div>
        </div>
        <Card className="p-6">
          <h2 className="font-semibold text-slate-900">How it works</h2>
          <ol className="mt-4 space-y-4">
            {[
              ['Choose a service', 'Pick an organization and one of its queues, e.g. General OPD or Fee Counter.'],
              ['Book or join', 'Reserve an appointment slot, or join today’s line as a walk-in and receive a token such as G-007.'],
              ['Check in and relax', 'On arrival your token joins the line. Your position and estimated wait update live.'],
              ['Get called', 'When staff call your token you are notified instantly, on any page of the app.'],
            ].map(([title, text], index) => (
              <li key={title} className="flex gap-3">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-indigo-600 text-sm font-semibold text-white">{index + 1}</span>
                <div>
                  <div className="font-medium text-slate-900">{title}</div>
                  <div className="text-sm text-slate-600">{text}</div>
                </div>
              </li>
            ))}
          </ol>
        </Card>
      </section>

      <section aria-labelledby="features">
        <h2 id="features" className="text-2xl font-semibold text-slate-900">What QueueLess does</h2>
        <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {features.map(({ icon: Icon, title, text }) => (
            <Card key={title}>
              <Icon className="size-6 text-indigo-600" aria-hidden />
              <h3 className="mt-3 font-medium text-slate-900">{title}</h3>
              <p className="mt-1 text-sm text-slate-600">{text}</p>
            </Card>
          ))}
        </div>
      </section>

      {organizations.data && organizations.data.length > 0 && (
        <section aria-labelledby="orgs">
          <h2 id="orgs" className="text-2xl font-semibold text-slate-900">Organizations on QueueLess</h2>
          <div className="mt-6 grid gap-4 sm:grid-cols-2">
            {organizations.data.slice(0, 4).map((organization) => (
              <Link key={organization.id} to={`/organizations/${organization.slug}`} className="group">
                <Card className="h-full transition-shadow group-hover:shadow-md">
                  <div className="font-medium text-slate-900 group-hover:text-indigo-700">{organization.name}</div>
                  {organization.description && <p className="mt-1 text-sm text-slate-600">{organization.description}</p>}
                </Card>
              </Link>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
