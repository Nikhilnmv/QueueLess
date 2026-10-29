import { useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import { Bell, LogOut, Menu, X } from 'lucide-react';
import { useLogout, useMe, useNotifications } from '../api/queries';
import { adminMemberships, staffMemberships } from '../auth/roles';
import { useUserEvents } from '../realtime/hooks';
import { cx } from './ui';

function Logo() {
  return (
    <Link to="/" className="flex items-center gap-2 font-semibold text-slate-900">
      <span className="flex size-8 items-center justify-center rounded-lg bg-indigo-600" aria-hidden>
        <span className="flex gap-0.5">
          <span className="size-1.5 rounded-full bg-indigo-200" />
          <span className="size-1.5 rounded-full bg-indigo-100" />
          <span className="size-1.5 rounded-full bg-white" />
        </span>
      </span>
      QueueLess
    </Link>
  );
}

function NavItem({ to, children, onClick }: { to: string; children: string; onClick?: () => void }) {
  return (
    <NavLink
      to={to}
      onClick={onClick}
      className={({ isActive }) => cx('rounded-md px-3 py-2 text-sm font-medium',
        isActive ? 'bg-indigo-50 text-indigo-700' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900')}
    >
      {children}
    </NavLink>
  );
}

export function Layout() {
  const me = useMe();
  const user = me.data ?? null;
  const notifications = useNotifications(!!user);
  const logout = useLogout();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  useUserEvents(!!user); // personal live stream while signed in

  const unread = notifications.data?.unreadCount ?? 0;
  const close = () => setMenuOpen(false);

  const links = (
    <>
      <NavItem to="/organizations" onClick={close}>Find a service</NavItem>
      {user && <NavItem to="/dashboard" onClick={close}>My queue</NavItem>}
      {staffMemberships(user).length > 0 && <NavItem to="/staff" onClick={close}>Staff</NavItem>}
      {adminMemberships(user).length > 0 && <NavItem to="/admin" onClick={close}>Admin</NavItem>}
      {user?.platformAdmin && <NavItem to="/platform" onClick={close}>Platform</NavItem>}
    </>
  );

  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2">
        Skip to content
      </a>
      <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4">
          <div className="flex items-center gap-6">
            <Logo />
            <nav className="hidden items-center gap-1 md:flex" aria-label="Main">{links}</nav>
          </div>
          <div className="flex items-center gap-2">
            {user ? (
              <>
                <Link to="/notifications" className="relative rounded-full p-2 text-slate-600 hover:bg-slate-100"
                      aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}>
                  <Bell className="size-5" />
                  {unread > 0 && (
                    <span className="absolute -right-0.5 -top-0.5 flex min-w-5 items-center justify-center rounded-full bg-rose-600 px-1 text-[11px] font-semibold text-white">
                      {unread > 99 ? '99+' : unread}
                    </span>
                  )}
                </Link>
                <span className="hidden text-sm text-slate-600 sm:inline" title={user.email}>{user.fullName}</span>
                <button
                  type="button"
                  onClick={() => logout.mutate(undefined, { onSettled: () => navigate('/') })}
                  className="rounded-full p-2 text-slate-600 hover:bg-slate-100"
                  aria-label="Log out"
                  title="Log out"
                >
                  <LogOut className="size-5" />
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="rounded-md px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-100">Log in</Link>
                <Link to="/register" className="rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-700">Sign up</Link>
              </>
            )}
            <button type="button" className="rounded-md p-2 text-slate-600 hover:bg-slate-100 md:hidden"
                    onClick={() => setMenuOpen((open) => !open)} aria-label="Menu" aria-expanded={menuOpen}>
              {menuOpen ? <X className="size-5" /> : <Menu className="size-5" />}
            </button>
          </div>
        </div>
        {menuOpen && <nav className="flex flex-col gap-1 border-t border-slate-200 px-4 py-2 md:hidden" aria-label="Mobile">{links}</nav>}
      </header>
      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        <Outlet />
      </main>
      <footer className="border-t border-slate-200 bg-white py-6 text-center text-sm text-slate-500">
        QueueLess · real-time queues and appointments · times are shown in each organization's time zone
      </footer>
    </div>
  );
}
