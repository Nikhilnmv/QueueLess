import { Link } from 'react-router';
import { useMarkAllNotificationsRead, useMarkNotificationRead, useNotifications } from '../api/queries';
import type { AppNotification } from '../api/types';
import { Button, Empty, ErrorNotice, Loading, PageTitle, cx } from '../components/ui';
import { relativeTime } from '../lib/format';

function NotificationRow({ notification, onRead }: { notification: AppNotification; onRead: (id: string) => void }) {
  const unread = !notification.readAt;
  const content = (
    <>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className={cx(unread ? 'font-semibold text-stone-900' : 'text-stone-700')}>
          {unread && <span className="mr-2 inline-block size-2 rounded-full bg-pine-600 align-middle" aria-label="Unread" />}
          {notification.title}
        </span>
        <time className="text-xs text-stone-500" dateTime={notification.createdAt}>{relativeTime(notification.createdAt)}</time>
      </div>
      <p className="mt-0.5 text-sm text-stone-600">{notification.message}</p>
    </>
  );
  const rowClass = cx('block w-full border-b border-stone-200 px-4 py-3 text-left last:border-0 hover:bg-stone-50', unread && 'bg-pine-50/50');
  return (
    <li>
      {notification.ticketId
        ? <Link to={`/tickets/${notification.ticketId}`} onClick={() => unread && onRead(notification.id)} className={rowClass}>{content}</Link>
        : <button type="button" onClick={() => unread && onRead(notification.id)} className={rowClass}>{content}</button>}
    </li>
  );
}

export function NotificationsPage() {
  const notifications = useNotifications();
  const markRead = useMarkNotificationRead();
  const markAll = useMarkAllNotificationsRead();

  return (
    <div className="mx-auto max-w-3xl">
      <PageTitle title="Notifications" description="Updates about your bookings and your turn. They stay here after you log out."
        actions={notifications.data && notifications.data.unreadCount > 0 && (
          <Button variant="secondary" loading={markAll.isPending} onClick={() => markAll.mutate()}>Mark all as read</Button>
        )} />
      {notifications.isPending && <Loading />}
      {notifications.isError && <ErrorNotice error={notifications.error} onRetry={() => notifications.refetch()} />}
      {notifications.data?.items.length === 0 && (
        <Empty>Nothing yet. You will be told here when a booking is confirmed, when your turn is near and when you are called.</Empty>
      )}
      {notifications.data && notifications.data.items.length > 0 && (
        <ul className="overflow-hidden rounded-md border border-stone-200 bg-white">
          {notifications.data.items.map((n) => <NotificationRow key={n.id} notification={n} onRead={(id) => markRead.mutate(id)} />)}
        </ul>
      )}
    </div>
  );
}
