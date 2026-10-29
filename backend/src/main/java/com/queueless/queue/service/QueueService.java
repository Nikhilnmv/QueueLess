package com.queueless.queue.service;

import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import com.queueless.membership.domain.Permission;
import com.queueless.membership.service.AccessControlService;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.service.OrganizationService;
import com.queueless.queue.api.QueueDtos.QueueResponse;
import com.queueless.queue.domain.QueueSettings;
import com.queueless.queue.domain.QueueStatus;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.queue.domain.ServiceQueueRepository;
import com.queueless.realtime.QueueChangeReason;
import com.queueless.realtime.QueueChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueueService {

    private final ServiceQueueRepository queues;
    private final OrganizationService organizations;
    private final AccessControlService access;
    private final QueueLockService locks;
    private final ApplicationEventPublisher events;
    private final TimeProvider time;

    public QueueService(ServiceQueueRepository queues, OrganizationService organizations, AccessControlService access,
            QueueLockService locks, ApplicationEventPublisher events, TimeProvider time) {
        this.queues = queues;
        this.organizations = organizations;
        this.access = access;
        this.locks = locks;
        this.events = events;
        this.time = time;
    }

    /** A queue together with its organization (both verified to exist and be active). */
    public record QueueContext(ServiceQueue queue, Organization organization) {}

    @Transactional(readOnly = true)
    public List<QueueResponse> listPublic(String organizationSlug) {
        Organization organization = organizations.requireActiveBySlug(organizationSlug);
        return queues.findByOrganizationIdOrderByNameAsc(organization.getId()).stream()
                .map(queue -> QueueResponse.from(queue, organization))
                .toList();
    }

    @Transactional(readOnly = true)
    public QueueResponse getPublic(UUID queueId) {
        QueueContext context = requireActiveQueue(queueId);
        return QueueResponse.from(context.queue(), context.organization());
    }

    @Transactional(readOnly = true)
    public QueueContext requireActiveQueue(UUID queueId) {
        ServiceQueue queue = queues.findById(queueId).orElseThrow(() -> ApiException.notFound("Queue"));
        return new QueueContext(queue, organizations.requireActive(queue.getOrganizationId()));
    }

    /** Organization of a queue without loading the queue entity (authorization before locking). */
    @Transactional(readOnly = true)
    public UUID requireOrganizationId(UUID queueId) {
        return queues.findOrganizationId(queueId).orElseThrow(() -> ApiException.notFound("Queue"));
    }

    @Transactional(readOnly = true)
    public List<QueueResponse> listForManagement(UUID organizationId, UUID actorId) {
        Organization organization = organizations.requireActive(organizationId);
        access.require(actorId, organizationId, Permission.VIEW_ORGANIZATION_MANAGEMENT);
        return queues.findByOrganizationIdOrderByNameAsc(organizationId).stream()
                .map(queue -> QueueResponse.from(queue, organization))
                .toList();
    }

    @Transactional
    public QueueResponse create(UUID organizationId, UUID actorId, QueueSettings settings) {
        access.require(actorId, organizationId, Permission.CONFIGURE_QUEUES);
        Organization organization = organizations.requireActive(organizationId);
        validate(settings);
        if (queues.existsByOrganizationIdAndNameIgnoreCase(organizationId, settings.name())) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, "A queue with this name already exists");
        }
        ServiceQueue queue = queues.saveAndFlush(new ServiceQueue(organizationId, settings, time.now()));
        return QueueResponse.from(queue, organization);
    }

    @Transactional
    public QueueResponse update(UUID queueId, UUID actorId, QueueSettings settings) {
        UUID organizationId = requireOrganizationId(queueId);
        access.require(actorId, organizationId, Permission.CONFIGURE_QUEUES);
        Organization organization = organizations.requireActive(organizationId);
        validate(settings);
        // Locked so capacity/schedule changes are serialized with bookings being made right now.
        ServiceQueue queue = locks.lock(queueId).queue();
        if (!queue.getName().equalsIgnoreCase(settings.name())
                && queues.existsByOrganizationIdAndNameIgnoreCase(organizationId, settings.name())) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, "A queue with this name already exists");
        }
        Instant now = time.now();
        queue.apply(settings, now);
        publish(queue, QueueChangeReason.QUEUE_SETTINGS_CHANGED, now);
        return QueueResponse.from(queue, organization);
    }

    @Transactional
    public QueueResponse changeStatus(UUID queueId, UUID actorId, QueueStatus status) {
        UUID organizationId = requireOrganizationId(queueId);
        access.require(actorId, organizationId, Permission.OPERATE_QUEUE);
        Organization organization = organizations.requireActive(organizationId);
        ServiceQueue queue = locks.lock(queueId).queue();
        if (queue.getStatus() != status) {
            Instant now = time.now();
            queue.changeStatus(status, now);
            publish(queue, QueueChangeReason.QUEUE_STATUS_CHANGED, now);
        }
        return QueueResponse.from(queue, organization);
    }

    private void publish(ServiceQueue queue, QueueChangeReason reason, Instant now) {
        long version = queue.bumpStateVersion(now);
        events.publishEvent(new QueueChangedEvent(queue.getId(), version, reason, now));
    }

    static void validate(QueueSettings settings) {
        if (!settings.closingTime().isAfter(settings.openingTime())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Closing time must be after opening time");
        }
        long openMinutes = Duration.between(settings.openingTime(), settings.closingTime()).toMinutes();
        if (openMinutes < settings.slotDurationMinutes()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "At least one appointment slot must fit between opening and closing time");
        }
    }
}
