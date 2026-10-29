import { createBrowserRouter } from 'react-router';
import { RequireAuth } from './auth/guards';
import { adminMemberships, staffMemberships } from './auth/roles';
import { Layout } from './components/Layout';
import { ToastProvider } from './components/Toast';
import { AccessDeniedPage } from './pages/AccessDeniedPage';
import { AppointmentPage } from './pages/AppointmentPage';
import { BookAppointmentPage } from './pages/BookAppointmentPage';
import { DashboardPage } from './pages/DashboardPage';
import { LandingPage } from './pages/LandingPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage, RouteErrorPage } from './pages/NotFoundPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OrganizationPage } from './pages/OrganizationPage';
import { OrganizationsPage } from './pages/OrganizationsPage';
import { QueuePage } from './pages/QueuePage';
import { RegisterPage } from './pages/RegisterPage';
import { TicketPage } from './pages/TicketPage';
import { AdminHomePage } from './pages/admin/AdminHomePage';
import { AdminOrganizationPage } from './pages/admin/AdminOrganizationPage';
import { PlatformPage } from './pages/platform/PlatformPage';
import { StaffHomePage } from './pages/staff/StaffHomePage';
import { StaffQueuePage } from './pages/staff/StaffQueuePage';
import type { CurrentUser } from './api/types';

const isStaff = (user: CurrentUser) => staffMemberships(user).length > 0;
const isAdmin = (user: CurrentUser) => adminMemberships(user).length > 0;
const isPlatformAdmin = (user: CurrentUser) => user.platformAdmin;

export const router = createBrowserRouter([
  {
    element: (
      <ToastProvider>
        <Layout />
      </ToastProvider>
    ),
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <LandingPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
      { path: 'organizations', element: <OrganizationsPage /> },
      { path: 'organizations/:slug', element: <OrganizationPage /> },
      { path: 'queues/:queueId', element: <QueuePage /> },
      { path: 'queues/:queueId/book', element: <RequireAuth><BookAppointmentPage /></RequireAuth> },
      { path: 'dashboard', element: <RequireAuth><DashboardPage /></RequireAuth> },
      { path: 'tickets/:ticketId', element: <RequireAuth><TicketPage /></RequireAuth> },
      { path: 'appointments/:appointmentId', element: <RequireAuth><AppointmentPage /></RequireAuth> },
      { path: 'notifications', element: <RequireAuth><NotificationsPage /></RequireAuth> },
      { path: 'staff', element: <RequireAuth allow={isStaff}><StaffHomePage /></RequireAuth> },
      { path: 'staff/queues/:queueId', element: <RequireAuth allow={isStaff}><StaffQueuePage /></RequireAuth> },
      { path: 'admin', element: <RequireAuth allow={isAdmin}><AdminHomePage /></RequireAuth> },
      { path: 'admin/organizations/:orgId', element: <RequireAuth allow={isStaff}><AdminOrganizationPage /></RequireAuth> },
      { path: 'platform', element: <RequireAuth allow={isPlatformAdmin}><PlatformPage /></RequireAuth> },
      { path: 'forbidden', element: <AccessDeniedPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
