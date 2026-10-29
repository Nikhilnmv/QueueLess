package com.queueless.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BookingReferenceGeneratorTest {

    @Test
    void referencesUseAnUnambiguousAlphabet() {
        BookingReferenceGenerator generator = new BookingReferenceGenerator(null);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String reference = generator.randomReference();
            assertThat(reference).matches("QL-[0-9A-HJKMNP-TV-Z]{6}"); // no I, L, O or U
            seen.add(reference);
        }
        assertThat(seen).hasSizeGreaterThan(990); // ~1 billion combinations: collisions are rare
    }
}
