package com.queueless.appointment.service;

import com.queueless.appointment.domain.AppointmentRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Human-friendly booking references such as {@code QL-7K3M9X}, using Crockford's base-32 alphabet
 * (no I, L, O or U, so references are easy to read aloud). 32^6 is about one billion values; the
 * existence check makes a collision practically impossible, and uq_appointments_reference rejects
 * one if it ever happened.
 */
@Component
public class BookingReferenceGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 6;
    private static final int MAX_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();
    private final AppointmentRepository appointments;

    public BookingReferenceGenerator(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    public String next() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomReference();
            if (!appointments.existsByBookingReference(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique booking reference");
    }

    String randomReference() {
        StringBuilder reference = new StringBuilder("QL-");
        for (int i = 0; i < LENGTH; i++) {
            reference.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return reference.toString();
    }
}
