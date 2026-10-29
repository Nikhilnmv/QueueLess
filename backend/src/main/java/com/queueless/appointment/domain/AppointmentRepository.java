package com.queueless.appointment.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * Bookings that consume the slot's capacity (everything except CANCELLED). Only meaningful as
     * a capacity check while holding the queue lock. Served by ix_appointments_queue_scheduled.
     */
    @Query("select count(a) from Appointment a where a.queueId = :queueId and a.scheduledAt = :scheduledAt "
            + "and a.status <> com.queueless.appointment.domain.AppointmentStatus.CANCELLED")
    long countCapacityUsed(UUID queueId, Instant scheduledAt);

    record SlotUsage(Instant scheduledAt, long booked) {}

    @Query("""
            select new com.queueless.appointment.domain.AppointmentRepository$SlotUsage(a.scheduledAt, count(a))
            from Appointment a
            where a.queueId = :queueId and a.scheduledAt >= :from and a.scheduledAt < :to
              and a.status <> com.queueless.appointment.domain.AppointmentStatus.CANCELLED
            group by a.scheduledAt""")
    List<SlotUsage> findSlotUsage(UUID queueId, Instant from, Instant to);

    Optional<Appointment> findByUserIdAndIdempotencyKey(UUID userId, UUID idempotencyKey);

    @Query("select a from Appointment a where a.queueId = :queueId and a.userId = :userId "
            + "and a.serviceDate = :serviceDate and a.status in :statuses")
    List<Appointment> findForUserOnDate(UUID queueId, UUID userId, LocalDate serviceDate,
            Collection<AppointmentStatus> statuses);

    boolean existsByBookingReference(String bookingReference);

    record AppointmentOwnership(UUID appointmentId, UUID queueId, UUID userId, UUID organizationId) {}

    @Query("""
            select new com.queueless.appointment.domain.AppointmentRepository$AppointmentOwnership(
                a.id, a.queueId, a.userId, q.organizationId)
            from Appointment a join ServiceQueue q on q.id = a.queueId
            where a.id = :appointmentId""")
    Optional<AppointmentOwnership> findOwnership(UUID appointmentId);

    /** Served by ix_appointments_user_status (leading user_id). */
    @Query("select a.id from Appointment a where a.userId = :userId order by a.scheduledAt desc")
    List<UUID> findIdsOfUser(UUID userId, Limit limit);
}
