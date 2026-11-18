package com.claims.policy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Indian-style amounts for messages: ₹3,00,000 and ₹40,000.5 (Java's NumberFormat has no lakh grouping). */
public final class Rupees {

    private Rupees() {
    }

    public static String format(BigDecimal amount) {
        BigDecimal v = amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);

        StringBuilder grouped = new StringBuilder();
        int n = whole.length();
        if (n <= 3) {
            grouped.append(whole);
        } else {
            String head = whole.substring(0, n - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) grouped.append(',');
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(whole.substring(n - 3));
        }
        return (v.signum() < 0 ? "-₹" : "₹") + grouped + fraction;
    }
}
