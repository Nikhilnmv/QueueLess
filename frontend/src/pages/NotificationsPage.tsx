import { Link } from 'react-router';
import { Bell, BellRing, CalendarCheck, CalendarX, CheckCheck, UserX } from 'lucide-react';
import { useMarkAllNotificationsRead, useMarkNotificationRead, useNotifications } from '../api/queries';
import type { AppNotification, NotificationType } from '../api/types';
import { Button, Card, EmptyState, ErrorState, PageHeader, Spinner, cx } from '../components/ui';
import { relativeTime } from '../lib/format';

const icons: Record<NotificationType, typeof Bell> = {
  APPOINTMENT_CONFIRMED: CalendarCheck,
  APPOINTMENT_CANCELLED: CalendarX,
  TICKET_CANCELLED: CalendarX,
  TURN_APPROACHING: Bell,
  TURN_CALLED: BellRing,
  MARKED_NO_SHOW: UserX,
};

function NotificationItem({ notification, onRead }: { notification: AppNotification; onRead: (id: string) => void }) {
  const Icon = icons[notification.type];
  const unread = !notification.readAt;
  const body = (
    <div className="flex gap-3">
      <Icon className={cx('mt-0.5 size-5 shrink-0', unread ? 'text-indigo-600' : 'text-slate-400')} aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className={cx('font-medium', unread ? 'text-slate-900' : 'text-slate-600')}>{notification.title}</span>
          <time className="text-xs text-slate-500" dateTime={notification.createdAt}>{relativeTime(notification.createdAt)}</time>
        </div>
        <p className="mt-0.5 text-sm text-slate-600">{notification.message}</p>
      </div>
      {unread && <span className="mt-2 size-2 shrink-0 rounded-full bg-indigo-600" aria-label="Unread" />}
    </div>
  );
  return (
    <li>
      {notification.ticketId ? (
        <Link to={`/tickets/${notification.ticketId}`} onClick={() => unread && onRead(notification.id)}
              className={cx('block rounded-xl p-4 ring-1 transition-colors hover:bg-slate-50', unread ? 'bg-indigo-50/40 ring-indigo-200' : 'bg-white ring-slate-200')}>
          {body}
        </Link>
      ) : (
        <button type="button" onClick={() => unread && onRead(notification.id)}
                className={cx('block w-full rounded-xl p-4 text-left ring-1', unread ? 'bg-indigo-50/40 ring-indigo-200' : 'bg-white ring-slate-200')}>
          {body}
        </button>
      )}
    </li>
  );
}

export function NotificationsPage() {
  const notifications = useNotifications();
  const markRead = useMarkNotificationRead();
  const markAll = useMarkAllNotificationsRead();

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title="Notifications" subtitle="Saved updates about your bookings and turns. They stay here after you log out."
        actions={notifications.data && notifications.data.unreadCount > 0 && (
          <Button variant="secondary" loading={markAll.isPending} icon={<CheckCheck className="size-4" aria-hidden />} onClick={() => markAll.mutate()}>
            Mark all as read
          </Button>
        )} />
      {notifications.isPending && <Spinner />}
      {notifications.isError && <ErrorState error={notifications.error} onRetry={() => notifications.refetch()} />}
      {notifications.data?.items.length === 0 && (
        <EmptyState icon={<Bell className="size-8" />} title="No notifications yet">
          You will be notified here when a booking is confirmed, when your turn is near and when you are called.
        </EmptyState>
      )}
      {notifications.data && notifications.data.items.length > 0 && (
        <Card className="p-3">
          <ul className="space-y-2">
            {notifications.data.items.map((n) => <NotificationItem key={n.id} notification={n} onRead={(id) => markRead.mutate(id)} />)}
          </ul>
        </Card>
      )}
    </div>
  );
}
