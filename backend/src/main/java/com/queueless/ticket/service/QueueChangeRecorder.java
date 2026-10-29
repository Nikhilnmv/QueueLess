package com.queueless.ticket.service;

import com.queueless.common.config.QueueLessProperties;
import com.queueless.common.time.TimeProvider;
import com.queueless.notification.domain.NotificationType;
import com.queueless.notification.service.NotificationService;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.queue.service.LockedQueue;
import com.queueless.realtime.QueueChangeReason;
import com.queueless.realtime.QueueChangedEvent;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.QueueTicketRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The common tail of every live-queue change, executed inside the changing transaction:
 * <ol>
 *   <li>notify waiting customers whose turn is now close (stored notifications, once per ticket),</li>
 *   <li>increment the queue's state version,</li>
 *   <li>publish a QueueChangedEvent, which is delivered to SSE subscribers only after commit.</li>
 * </ol>
 */
@Component
public class QueueChangeRecorder {

    private final QueueTicketRepository tickets;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final TimeProvider time;
    private final int approachPosition;

    public QueueChangeRecorder(QueueTicketRepository tickets, NotificationService notifications,
            ApplicationEventPublisher events, TimeProvider time, QueueLessProperties properties) {
        this.tickets = tickets;
        this.notifications = notifications;
        this.events = events;
        this.time = time;
        this.approachPosition = properties.notifications().approachPosition();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long record(LockedQueue locked, LocalDate serviceDate, QueueChangeReason reason) {
        Instant now = time.now();
        notifyApproachingTurns(locked.queue(), serviceDate, now);
        long version = locked.queue().bumpStateVersion(now);
        events.publishEvent(new QueueChangedEvent(locked.id(), version, reason, now));
        return version;
    }

    private void notifyApproachingTurns(ServiceQueue queue, LocalDate serviceDate, Instant now) {
        List<QueueTicket> front = tickets.findWaitingInServiceOrder(queue.getId(), serviceDate, Limit.of(approachPosition));
        for (int index = 0; index < front.size(); index++) {
            QueueTicket ticket = front.get(index);
            if (ticket.getUserId() == null || ticket.getApproachNotifiedAt() != null) {
                continue;
            }
            int position = index + 1;
            String token = queue.displayToken(ticket.getTokenNumber());
            String message = position == 1
                    ? "You are next in line for " + queue.getName() + " (token " + token + "). Please be ready."
                    : "You are number " + position + " in line for " + queue.getName() + " (token " + token
                            + "). Please make your way to the counter area.";
            ticket.markApproachNotified(now);
            notifications.notify(ticket.getUserId(), ticket.getId(), NotificationType.TURN_APPROACHING,
                    "Your turn is coming up", message);
        }
    }
}
