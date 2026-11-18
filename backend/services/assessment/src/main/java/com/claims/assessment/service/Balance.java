package com.claims.assessment.service;

import java.math.BigDecimal;

/** The policy's remaining sum insured (Policy's {@code GetBalance}: sum insured − utilised − reserved). */
public record Balance(BigDecimal remaining) {
}
