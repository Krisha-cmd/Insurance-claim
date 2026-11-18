package com.claims.policy.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Fresh policies (on the seeded plan and policyholder) so tests never share a balance. */
public final class TestPolicies {

    public static final UUID PATIENT1_USER = UUID.fromString("4e33233c-a255-4c75-963a-6809cd3b0cdd");
    public static final UUID PATIENT1_HOLDER = UUID.fromString("a2b56321-c8b8-4159-93d6-3cf613b3c556");
    public static final UUID PLAN = UUID.fromString("787465cc-3880-4abe-8d7b-463662e0ff9b");

    private TestPolicies() {
    }

    /** @return the policy number */
    public static String create(JdbcTemplate jdbc, String sumInsured, String status) {
        UUID id = UUID.randomUUID();
        String policyNo = "POL-T-" + id.toString().substring(0, 8);
        jdbc.update("""
                insert into policy (id, policy_no, policyholder_id, plan_id, start_date, end_date, sum_insured, status)
                values (?, ?, ?, ?, current_date - 30, current_date + 300, ?, ?)""",
                id, policyNo, PATIENT1_HOLDER, PLAN, new BigDecimal(sumInsured), status);
        jdbc.update("insert into insured_member (id, policy_id, full_name, relation) values (?, ?, 'Asha Verma', 'SELF')",
                UUID.randomUUID(), id);
        jdbc.update("insert into policy_balance (policy_id) values (?)", id);
        return policyNo;
    }

    public record Balance(BigDecimal utilised, BigDecimal reserved) {
    }

    public static Balance balance(JdbcTemplate jdbc, String policyNo) {
        Map<String, Object> row = jdbc.queryForMap("""
                select b.utilised, b.reserved from policy_balance b join policy p on p.id = b.policy_id where p.policy_no = ?""",
                policyNo);
        return new Balance((BigDecimal) row.get("utilised"), (BigDecimal) row.get("reserved"));
    }
}
