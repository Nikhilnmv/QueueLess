import { useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import { useLogout, useMe, useNotifications } from '../api/queries';
import { adminMemberships, staffMemberships } from '../auth/roles';
import { useUserEvents } from '../realtime/hooks';
import { cx } from './ui';

function Logo() {
  return (
    <Link to="/" className="flex items-center gap-2 text-lg font-semibold tracking-tight text-stone-900">
      <img src="/favicon.svg" alt="" className="size-6" />
      QueueLess
    </Link>
  );
}

function NavItem({ to, children, onClick }: { to: string; children: string; onClick?: () => void }) {
  return (
    <NavLink
      to={to}
      onClick={onClick}
      className={({ isActive }) => cx('px-1 py-1 text-sm underline-offset-[6px]',
        isActive ? 'font-medium text-stone-900 underline decoration-pine-600 decoration-2' : 'text-stone-600 hover:text-stone-900')}
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
      {user && <NavItem to="/dashboard" onClick={close}>My visits</NavItem>}
      {staffMemberships(user).length > 0 && <NavItem to="/staff" onClick={close}>Staff</NavItem>}
      {adminMemberships(user).length > 0 && <NavItem to="/admin" onClick={close}>Admin</NavItem>}
      {user?.platformAdmin && <NavItem to="/platform" onClick={close}>Platform</NavItem>}
    </>
  );

  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:bg-white focus:px-3 focus:py-2">
        Skip to content
      </a>
      <header className="border-b border-stone-200 bg-white">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between gap-4 px-4">
          <div className="flex items-center gap-8">
            <Logo />
            <nav className="hidden items-center gap-5 md:flex" aria-label="Main">{links}</nav>
          </div>
          <div className="flex items-center gap-4 text-sm">
            {user ? (
              <>
                <Link to="/notifications" className="text-stone-600 hover:text-stone-900">
                  Notifications
                  {unread > 0 && (
                    <span className="ml-1.5 rounded bg-pine-700 px-1.5 py-0.5 text-xs font-semibold text-white" aria-label={`${unread} unread`}>
                      {unread > 99 ? '99+' : unread}
                    </span>
                  )}
                </Link>
                <span className="hidden text-stone-500 lg:inline" title={user.email}>{user.fullName}</span>
                <button
                  type="button"
                  onClick={() => {
                    // Leave protected pages first, so they do not redirect to the login page.
                    navigate('/');
                    logout.mutate();
                  }}
                  className="text-stone-600 underline-offset-2 hover:text-stone-900 hover:underline"
                >
                  Log out
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="text-stone-700 hover:text-stone-900">Log in</Link>
                <Link to="/register" className="rounded bg-pine-700 px-3 py-1.5 font-medium text-white hover:bg-pine-800">Sign up</Link>
              </>
            )}
            <button type="button" className="rounded border border-stone-300 px-2 py-1 text-stone-700 md:hidden"
                    onClick={() => setMenuOpen((open) => !open)} aria-expanded={menuOpen}>
              Menu
            </button>
          </div>
        </div>
        {menuOpen && <nav className="flex flex-col gap-2 border-t border-stone-200 px-4 py-3 md:hidden" aria-label="Mobile">{links}</nav>}
      </header>
      <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <Outlet />
      </main>
      <footer className="border-t border-stone-200 py-5">
        <div className="mx-auto flex max-w-5xl flex-wrap justify-between gap-2 px-4 text-xs text-stone-500">
          <span>QueueLess · queue and appointment system</span>
          <span>Times are shown in each organization's local time</span>
        </div>
      </footer>
    </div>
  );
}
