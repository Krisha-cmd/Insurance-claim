package com.claims.policy.service;

import com.claims.policy.domain.PolicyBalance;
import com.claims.policy.domain.TreatmentCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainTest {

    @Test
    void balanceArithmetic() {
        PolicyBalance b = new PolicyBalance(UUID.randomUUID());
        BigDecimal sum = new BigDecimal("500000");
        b.reserve(new BigDecimal("60000"));
        assertThat(b.remaining(sum)).isEqualByComparingTo("440000");
        b.commit(new BigDecimal("60000"));
        assertThat(b.getUtilised()).isEqualByComparingTo("60000");
        assertThat(b.getReserved()).isEqualByComparingTo("0");
        assertThat(b.remaining(sum)).isEqualByComparingTo("440000");
        b.reserve(new BigDecimal("1000"));
        b.release(new BigDecimal("1000"));
        assertThat(b.remaining(sum)).isEqualByComparingTo("440000");
        assertThatThrownBy(() -> b.release(BigDecimal.ONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void treatmentCategoriesMatchTheFrontendLabels() {
        assertThat(TreatmentCategory.parse("Day care procedure")).contains(TreatmentCategory.DAYCARE);
        assertThat(TreatmentCategory.parse("hospitalisation")).contains(TreatmentCategory.HOSPITALISATION);
        assertThat(TreatmentCategory.parse("Emergency (ambulance)")).contains(TreatmentCategory.EMERGENCY);
        assertThat(TreatmentCategory.parse("Acupuncture")).isEmpty();
    }

    @Test
    void rupees() {
        assertThat(LimitReservationParticipant.rupees(new BigDecimal("300000"))).isEqualTo("₹3,00,000");
        assertThat(LimitReservationParticipant.rupees(new BigDecimal("40000.50"))).isEqualTo("₹40,000.5");
        assertThat(LimitReservationParticipant.rupees(new BigDecimal("12345678.00"))).isEqualTo("₹1,23,45,678");
        assertThat(LimitReservationParticipant.rupees(new BigDecimal("999"))).isEqualTo("₹999");
        assertThat(LimitReservationParticipant.rupees(new BigDecimal("5000"))).isEqualTo("₹5,000");
    }
}
