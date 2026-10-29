-- QueueLess schema, part 3: persistent in-app notifications.

CREATE TABLE notifications (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid         NOT NULL REFERENCES users (id),
    ticket_id  uuid         REFERENCES queue_tickets (id),
    type       varchar(32)  NOT NULL,
    title      varchar(120) NOT NULL,
    message    varchar(500) NOT NULL,
    read_at    timestamptz,
    created_at timestamptz  NOT NULL,
    CONSTRAINT ck_notifications_type CHECK (type IN (
        'APPOINTMENT_CONFIRMED', 'APPOINTMENT_CANCELLED', 'TICKET_CANCELLED',
        'TURN_APPROACHING', 'TURN_CALLED', 'MARKED_NO_SHOW'))
);

-- Notification list of a user, newest first.
CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);

-- Unread badge count.
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;

-- A ticket is told at most once that its turn is approaching and once that it was called.
CREATE UNIQUE INDEX ux_notifications_ticket_once
    ON notifications (ticket_id, type)
    WHERE type IN ('TURN_APPROACHING', 'TURN_CALLED');
