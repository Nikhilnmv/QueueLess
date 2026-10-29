import { useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { invalidateQueue, keys } from '../api/queries';
import type { NotificationEvent, NotificationList } from '../api/types';
import { useToast } from '../components/Toast';
import { useEventStream } from './useEventStream';

/** Live updates for one queue: every event (and every reconnect) refreshes the queue's data. */
export function useQueueEvents(queueId: string | undefined, enabled = true) {
  const client = useQueryClient();
  const refresh = useCallback(() => {
    if (queueId) invalidateQueue(client, queueId);
  }, [client, queueId]);
  const checkSession = useCallback(() => void client.invalidateQueries({ queryKey: keys.me }), [client]);
  return useEventStream(enabled && queueId ? `/api/queues/${queueId}/events` : null,
    { 'queue-changed': refresh }, refresh, checkSession);
}

/** The signed-in user's personal stream: new notifications appear without a reload. */
export function useUserEvents(enabled: boolean) {
  const client = useQueryClient();
  const toast = useToast();

  const onNotification = useCallback(async (data: unknown) => {
    const event = data as NotificationEvent;
    void client.invalidateQueries({ queryKey: ['my'] });
    void client.invalidateQueries({ queryKey: ['ticket'] });
    void client.invalidateQueries({ queryKey: ['appointment'] });
    try {
      const list = await api<NotificationList>('/api/me/notifications?limit=5');
      client.setQueryData(keys.notifications, (old: NotificationList | undefined) =>
        old ? { ...old, unreadCount: list.unreadCount } : old);
      const notification = list.items.find((n) => n.id === event.notificationId);
      if (notification) {
        toast.show({
          title: notification.title,
          message: notification.message,
          tone: notification.type === 'TURN_CALLED' ? 'success' : notification.type === 'MARKED_NO_SHOW' ? 'warning' : 'info',
          href: notification.ticketId ? `/tickets/${notification.ticketId}` : '/notifications',
        });
      }
    } catch {
      // The bell count still refreshes through the invalidation above.
    }
  }, [client, toast]);

  const refresh = useCallback(() => void client.invalidateQueries({ queryKey: keys.notifications }), [client]);
  const checkSession = useCallback(() => void client.invalidateQueries({ queryKey: keys.me }), [client]);
  return useEventStream(enabled ? '/api/me/events' : null, { notification: onNotification }, refresh, checkSession);
}
