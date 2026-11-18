package com.claims.policy.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Coverage rule categories. {@code label} is the text a claim carries as its treatmentType
 * (TREATMENT_TYPES in frontend/src/lib/status.js), so Eligibility can match a claim to its rule.
 */
public enum TreatmentCategory {
    HOSPITALISATION("Hospitalisation"),
    SURGERY("Surgery"),
    DAYCARE("Day care procedure"),
    MATERNITY("Maternity"),
    DIAGNOSTICS("Diagnostics"),
    EMERGENCY("Emergency (ambulance)"),
    COSMETIC("Cosmetic treatment");

    private final String label;

    TreatmentCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** By code ("DAYCARE") or label ("Day care procedure"), ignoring case. */
    public static Optional<TreatmentCategory> parse(String value) {
        if (value == null) return Optional.empty();
        String v = value.trim();
        for (TreatmentCategory c : values()) {
            if (c.name().equalsIgnoreCase(v) || c.label.equalsIgnoreCase(v)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** "cosmetic", used as the exclusion keyword. */
    public String keyword() {
        return name().toLowerCase(Locale.ROOT);
    }
}
