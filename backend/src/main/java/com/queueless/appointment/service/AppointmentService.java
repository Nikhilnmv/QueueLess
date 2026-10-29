package com.queueless.appointment.service;

import com.queueless.appointment.api.AppointmentDtos.AppointmentResponse;
import com.queueless.appointment.api.AppointmentDtos.SlotResponse;
import com.queueless.appointment.api.AppointmentDtos.SlotsResponse;
import com.queueless.appointment.domain.Appointment;
import com.queueless.appointment.domain.AppointmentRepository;
import com.queueless.appointment.domain.AppointmentRepository.AppointmentOwnership;
import com.queueless.appointment.domain.AppointmentRepository.SlotUsage;
import com.queueless.appointment.domain.AppointmentStatus;
import com.queueless.appointment.service.BookingRules.ValidatedSlot;
import com.queueless.common.config.QueueLessProperties;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import com.queueless.membership.domain.Permission;
import com.queueless.membership.service.AccessControlService;
import com.queueless.membership.service.MembershipService;
import com.queueless.notification.domain.NotificationType;
import com.queueless.notification.service.NotificationService;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.domain.OrganizationRepository;
import com.queueless.organization.service.OrganizationService;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.queue.domain.ServiceQueueRepository;
import com.queueless.queue.service.LockedQueue;
import com.queueless.queue.service.QueueLockService;
import com.queueless.queue.service.QueueService;
import com.queueless.queue.service.QueueService.QueueContext;
import com.queueless.realtime.QueueChangeReason;
import com.queueless.ticket.api.TicketDtos.TicketResponse;
import com.queueless.ticket.domain.QueueTicket;
import com.queueless.ticket.domain.QueueTicketRepository;
import com.queueless.ticket.service.QueueChangeRecorder;
import com.queueless.ticket.service.TicketService;
import com.queueless.ticket.service.TicketViews;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appointment booking, check-in and cancellation. Writes follow the same pattern as the ticket
 * module: authorize, lock the queue row, re-read state, validate, change, record the queue change.
 */
@Service
public class AppointmentService {

    private static final int MY_APPOINTMENTS_LIMIT = 50;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    public record BookingResult(AppointmentResponse appointment, boolean replayed) {}

    private final AppointmentRepository appointments;
    private final QueueTicketRepository tickets;
    private final QueueService queues;
    private final ServiceQueueRepository queueRepository;
    private final OrganizationRepository organizationRepository;
    private final QueueLockService locks;
    private final OrganizationService organizations;
    private final AccessControlService access;
    private final MembershipService memberships;
    private final TicketService ticketService;
    private final TicketViews ticketViews;
    private final QueueChangeRecorder changes;
    private final NotificationService notifications;
    private final BookingReferenceGenerator references;
    private final AppointmentViews views;
    private final TimeProvider time;
    private final int maxDaysAhead;

    public AppointmentService(AppointmentRepository appointments, QueueTicketRepository tickets, QueueService queues,
            ServiceQueueRepository queueRepository, OrganizationRepository organizationRepository,
            QueueLockService locks, OrganizationService organizations, AccessControlService access,
            MembershipService memberships, TicketService ticketService, TicketViews ticketViews,
            QueueChangeRecorder changes, NotificationService notifications, BookingReferenceGenerator references,
            AppointmentViews views, TimeProvider time, QueueLessProperties properties) {
        this.appointments = appointments;
        this.tickets = tickets;
        this.queues = queues;
        this.queueRepository = queueRepository;
        this.organizationRepository = organizationRepository;
        this.locks = locks;
        this.organizations = organizations;
        this.access = access;
        this.memberships = memberships;
        this.ticketService = ticketService;
        this.ticketViews = ticketViews;
        this.changes = changes;
        this.notifications = notifications;
        this.references = references;
        this.views = views;
        this.time = time;
        this.maxDaysAhead = properties.booking().maxDaysAhead();
    }

    /** Public availability of one organization-local day (no customer data). */
    @Transactional(readOnly = true)
    public SlotsResponse slots(UUID queueId, LocalDate requestedDate) {
        QueueContext context = queues.requireActiveQueue(queueId);
        ServiceQueue queue = context.queue();
        ZoneId zone = context.organization().zoneId();
        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate lastDate = today.plusDays(maxDaysAhead);
        LocalDate date = requestedDate == null ? today : requestedDate;
        if (date.isBefore(today) || date.isAfter(lastDate)) {
            throw new ApiException(ErrorCode.INVALID_SLOT, "Choose a date between " + today + " and " + lastDate);
        }
        Map<Instant, Long> usage = appointments.findSlotUsage(queueId, date.atStartOfDay(zone).toInstant(),
                        date.plusDays(1).atStartOfDay(zone).toInstant()).stream()
                .collect(Collectors.toMap(SlotUsage::scheduledAt, SlotUsage::booked));
        List<SlotResponse> slots = SlotSchedule.slotStarts(queue.getOpeningTime(), queue.getClosingTime(),
                        queue.getSlotDurationMinutes()).stream()
                .map(start -> {
                    Instant startsAt = ZonedDateTime.of(date, start, zone).toInstant();
                    long booked = usage.getOrDefault(startsAt, 0L);
                    long available = Math.max(0, queue.getCapacityPerSlot() - booked);
                    boolean bookable = queue.isBookingEnabled() && startsAt.isAfter(now) && available > 0;
                    return new SlotResponse(startsAt, start, queue.getCapacityPerSlot(), booked, available, bookable);
                })
                .toList();
        return new SlotsResponse(queueId, date, zone.getId(), queue.isBookingEnabled(), queue.getSlotDurationMinutes(),
                today, lastDate, slots);
    }

    /**
     * Books a slot and reserves its token in one transaction. The queue lock makes the capacity
     * check and the insert atomic with respect to other bookings of the same queue, so two
     * customers can never both take the last place in a slot.
     */
    @Transactional
    public BookingResult book(UUID queueId, UUID userId, Instant requestedStart, UUID idempotencyKey) {
        LockedQueue locked = locks.lock(queueId);
        ServiceQueue queue = locked.queue();
        Organization organization = organizations.requireActive(queue.getOrganizationId());

        // A repeated submission with the same Idempotency-Key returns the booking already made.
        // It is checked under the lock, so a concurrent duplicate waits and then finds the first one.
        if (idempotencyKey != null) {
            Optional<Appointment> previous = appointments.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (previous.isPresent()) {
                Appointment existing = previous.get();
                if (!existing.getQueueId().equals(queueId) || !existing.getScheduledAt().equals(requestedStart)) {
                    throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "This Idempotency-Key was already used for a different booking");
                }
                QueueTicket ticket = tickets.findByAppointmentId(existing.getId()).orElse(null);
                return new BookingResult(views.describe(existing, ticket, queue, organization), true);
            }
        }

        if (!queue.isBookingEnabled()) {
            throw new ApiException(ErrorCode.BOOKING_DISABLED, "This queue is not taking appointment bookings");
        }
        Instant now = time.now();
        ValidatedSlot slot = BookingRules.validate(queue, organization.zoneId(), requestedStart, now, maxDaysAhead);

        if (appointments.countCapacityUsed(queueId, slot.startsAt()) >= queue.getCapacityPerSlot()) {
            throw new ApiException(ErrorCode.SLOT_FULL, "This slot is fully booked. Please choose another time.");
        }
        if (!appointments.findForUserOnDate(queueId, userId, slot.serviceDate(),
                EnumSet.of(AppointmentStatus.BOOKED, AppointmentStatus.CHECKED_IN)).isEmpty()) {
            throw new ApiException(ErrorCode.ALREADY_BOOKED, "You already have a booking in this queue on that day");
        }

        Appointment appointment = appointments.saveAndFlush(new Appointment(queueId, userId, slot.startsAt(),
                slot.serviceDate(), references.next(), idempotencyKey, now));
        QueueTicket ticket = ticketService.reserveForAppointment(locked, appointment.getId(), userId, slot.serviceDate());
        memberships.ensureCustomerMembership(organization.getId(), userId);
        notifications.notify(userId, ticket.getId(), NotificationType.APPOINTMENT_CONFIRMED,
                "Booking " + appointment.getBookingReference() + " confirmed",
                queue.getName() + " at " + organization.getName() + " on "
                        + WHEN.format(slot.startsAt().atZone(organization.zoneId())) + ". Your token is "
                        + queue.displayToken(ticket.getTokenNumber()) + ". Check in when you arrive.");
        changes.record(locked, slot.serviceDate(), QueueChangeReason.APPOINTMENT_BOOKED);
        return new BookingResult(views.describe(appointment, ticket, queue, organization), false);
    }

    /** BOOKED -> CHECKED_IN (ticket BOOKED -> WAITING). Owner self check-in or staff at the desk. */
    @Transactional
    public TicketResponse checkIn(UUID appointmentId, UUID actorId) {
        AppointmentOwnership ownership = requireOwnerOrStaff(appointmentId, actorId);
        LockedQueue locked = locks.lock(ownership.queueId());
        ServiceQueue queue = locked.queue();
        Organization organization = organizations.requireActive(ownership.organizationId());
        Appointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> ApiException.notFound("Appointment"));
        QueueTicket ticket = requireTicket(appointmentId);

        if (appointment.getStatus() == AppointmentStatus.CHECKED_IN) {
            return ticketViews.describe(ticket, queue, organization); // repeated check-in is harmless
        }
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only booked appointments can be checked in").with("currentStatus", appointment.getStatus().name());
        }
        Instant now = time.now();
        LocalDate today = LocalDate.ofInstant(now, organization.zoneId());
        if (appointment.getServiceDate().isAfter(today)) {
            throw new ApiException(ErrorCode.CHECK_IN_NOT_ALLOWED, "Check-in opens on the day of the appointment")
                    .with("checkInOpensAt", views.checkInOpensAt(appointment));
        }
        if (appointment.getServiceDate().isBefore(today)) {
            throw new ApiException(ErrorCode.CHECK_IN_NOT_ALLOWED, "This appointment's date has passed");
        }
        Instant opensAt = views.checkInOpensAt(appointment);
        if (now.isBefore(opensAt)) {
            throw new ApiException(ErrorCode.CHECK_IN_NOT_ALLOWED, "Check-in opens at "
                    + DateTimeFormatter.ofPattern("HH:mm").format(opensAt.atZone(organization.zoneId())))
                    .with("checkInOpensAt", opensAt);
        }
        if (!queue.acceptsNewArrivals()) {
            throw new ApiException(ErrorCode.QUEUE_NOT_ACCEPTING, "The queue is closed; check-in is not possible now");
        }
        ticketService.checkIn(locked, ticket, actorId);
        return ticketViews.describe(ticket, queue, organization);
    }

    /** Cancels a booking and its ticket together; repeating it returns the cancelled booking. */
    @Transactional
    public AppointmentResponse cancel(UUID appointmentId, UUID actorId) {
        AppointmentOwnership ownership = requireOwnerOrStaff(appointmentId, actorId);
        LockedQueue locked = locks.lock(ownership.queueId());
        Organization organization = organizations.requireActive(ownership.organizationId());
        Appointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> ApiException.notFound("Appointment"));
        QueueTicket ticket = requireTicket(appointmentId);

        if (appointment.getStatus() != AppointmentStatus.CANCELLED) {
            if (!appointment.getStatus().isActive()) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "Completed or missed appointments cannot be cancelled")
                        .with("currentStatus", appointment.getStatus().name());
            }
            // Rejects tickets already at the counter; otherwise cancels the ticket, which (through
            // AppointmentTicketSync) cancels the appointment in the same transaction.
            ticketService.cancelLocked(locked, organization, ticket, actorId, QueueChangeReason.APPOINTMENT_CANCELLED);
            appointment.cancel(time.now(), actorId);
        }
        return views.describe(appointment, ticket, locked.queue(), organization);
    }

    @Transactional(readOnly = true)
    public AppointmentResponse get(UUID appointmentId, UUID actorId) {
        AppointmentOwnership ownership = requireOwnerOrStaff(appointmentId, actorId);
        QueueContext context = queues.requireActiveQueue(ownership.queueId());
        Appointment appointment = appointments.findById(appointmentId)
                .orElseThrow(() -> ApiException.notFound("Appointment"));
        return views.describe(appointment, tickets.findByAppointmentId(appointmentId).orElse(null), context.queue(),
                context.organization());
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> listMine(UUID userId) {
        List<UUID> ids = appointments.findIdsOfUser(userId, Limit.of(MY_APPOINTMENTS_LIMIT));
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Appointment> byId = appointments.findAllById(ids).stream()
                .collect(Collectors.toMap(Appointment::getId, Function.identity()));
        Map<UUID, QueueTicket> ticketsByAppointment = tickets.findByAppointmentIdIn(ids).stream()
                .collect(Collectors.toMap(QueueTicket::getAppointmentId, Function.identity()));
        Map<UUID, ServiceQueue> queuesById = queueRepository.findAllById(
                        byId.values().stream().map(Appointment::getQueueId).distinct().toList()).stream()
                .collect(Collectors.toMap(ServiceQueue::getId, Function.identity()));
        Map<UUID, Organization> organizationsById = organizationRepository.findAllById(
                        queuesById.values().stream().map(ServiceQueue::getOrganizationId).distinct().toList()).stream()
                .filter(Organization::isActive)
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
        return ids.stream()
                .map(byId::get)
                .filter(a -> organizationsById.containsKey(queuesById.get(a.getQueueId()).getOrganizationId()))
                .map(a -> {
                    ServiceQueue queue = queuesById.get(a.getQueueId());
                    return views.describe(a, ticketsByAppointment.get(a.getId()), queue,
                            organizationsById.get(queue.getOrganizationId()));
                })
                .toList();
    }

    /** Owner, or staff of the appointment's organization; anyone else gets 404 (not revealed). */
    private AppointmentOwnership requireOwnerOrStaff(UUID appointmentId, UUID actorId) {
        AppointmentOwnership ownership = appointments.findOwnership(appointmentId)
                .orElseThrow(() -> ApiException.notFound("Appointment"));
        boolean owner = actorId.equals(ownership.userId());
        if (!owner && !access.has(actorId, ownership.organizationId(), Permission.MANAGE_CUSTOMER_BOOKINGS)) {
            throw ApiException.notFound("Appointment");
        }
        return ownership;
    }

    private QueueTicket requireTicket(UUID appointmentId) {
        return tickets.findByAppointmentId(appointmentId)
                .orElseThrow(() -> new IllegalStateException("Appointment " + appointmentId + " has no ticket"));
    }
}
