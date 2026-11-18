package com.claims.policy.mapper;

import com.claims.policy.api.dto.CoverageResponse;
import com.claims.policy.api.dto.CoverageRuleDto;
import com.claims.policy.api.dto.MemberDto;
import com.claims.policy.api.dto.PolicyResponse;
import com.claims.policy.config.PolicyProperties;
import com.claims.policy.domain.CoverageRule;
import com.claims.policy.domain.InsuredMember;
import com.claims.policy.domain.Plan;
import com.claims.policy.domain.Policy;
import com.claims.policy.domain.PolicyBalance;
import com.claims.policy.domain.Rupees;
import com.claims.policy.domain.TreatmentCategory;
import com.claims.policy.service.PolicyQueryService.CoverageView;
import com.claims.policy.service.PolicyQueryService.PolicyView;
import com.claims.proto.policy.v1.BalanceDetails;
import com.claims.proto.policy.v1.CoverageDetails;
import com.claims.proto.policy.v1.Member;
import com.claims.proto.policy.v1.PolicyDetails;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PolicyMapper {

    private final String currency;

    public PolicyMapper(PolicyProperties props) {
        this.currency = props.currency();
    }

    public PolicyResponse policy(PolicyView v) {
        Policy p = v.policy();
        Plan plan = p.getPlan();
        PolicyBalance b = v.balance();
        return new PolicyResponse(p.getId(), p.getPolicyNo(), p.getPolicyholder().getId(), v.holderName(), plan.getName(),
                p.getStatus().name(), p.getStartDate(), p.getEndDate(), p.getSumInsured(), b.getUtilised(), b.getReserved(),
                b.remaining(p.getSumInsured()), plan.getDeductible(), plan.getCopayPct(), plan.getRoomRentLimit(), currency,
                v.members().stream().map(this::member).toList());
    }

    public MemberDto member(InsuredMember m) {
        return new MemberDto(m.getId(), m.getFullName(), m.getRelation().name(), m.getDateOfBirth());
    }

    public CoverageResponse coverage(CoverageView v) {
        Policy p = v.policy();
        Plan plan = p.getPlan();
        return new CoverageResponse(p.getPolicyNo(), plan.getName(), p.getStartDate(), p.getEndDate(), p.getSumInsured(),
                plan.getDeductible(), plan.getCopayPct(), plan.getRoomRentLimit(),
                v.rules().stream().map(r -> rule(r, plan)).toList(),
                v.rules().stream().filter(r -> !r.isCovered())
                        .map(r -> new CoverageResponse.Exclusion(r.getTreatmentCategory().keyword(), r.getTreatmentCategory().label()))
                        .toList());
    }

    private CoverageRuleDto rule(CoverageRule r, Plan plan) {
        String notes = "";
        if (!r.isCovered()) notes = "Not covered on " + plan.getName();
        else if (r.getTreatmentCategory() == TreatmentCategory.HOSPITALISATION && plan.getRoomRentLimit() != null)
            notes = "Room rent up to " + rupees(plan.getRoomRentLimit()) + "/day";
        else if (r.getWaitingPeriodDays() > 0) notes = r.getWaitingPeriodDays() + "-day waiting period from the policy start";
        return new CoverageRuleDto(r.getTreatmentCategory().name(), r.getTreatmentCategory().label(), r.isCovered(),
                r.isCovered() ? r.getSubLimit() : null, r.getWaitingPeriodDays(), notes);
    }

    // ------------------------------------------------------------------ gRPC

    public PolicyDetails details(PolicyView v) {
        Policy p = v.policy();
        PolicyDetails.Builder b = PolicyDetails.newBuilder()
                .setPolicyId(p.getId().toString()).setPolicyNo(p.getPolicyNo())
                .setPolicyholderId(p.getPolicyholder().getId().toString()).setPolicyholderName(v.holderName())
                .setPlanName(p.getPlan().getName()).setStatus(p.getStatus().name())
                .setStartDate(p.getStartDate().toString()).setEndDate(p.getEndDate().toString())
                .setSumInsured(p.getSumInsured().toPlainString());
        for (InsuredMember m : v.members()) {
            b.addMembers(Member.newBuilder().setMemberId(m.getId().toString()).setFullName(m.getFullName())
                    .setRelation(m.getRelation().name())
                    .setDateOfBirth(m.getDateOfBirth() == null ? "" : m.getDateOfBirth().toString()));
        }
        return b.build();
    }

    public CoverageDetails coverageDetails(CoverageView v) {
        Policy p = v.policy();
        Plan plan = p.getPlan();
        CoverageDetails.Builder b = CoverageDetails.newBuilder()
                .setPolicyNo(p.getPolicyNo()).setPlanName(plan.getName())
                .setDeductible(plan.getDeductible().toPlainString()).setCopayPct(plan.getCopayPct().toPlainString())
                .setRoomRentLimit(plan.getRoomRentLimit() == null ? "" : plan.getRoomRentLimit().toPlainString())
                .setStartDate(p.getStartDate().toString()).setEndDate(p.getEndDate().toString());
        for (CoverageRule r : v.rules()) {
            b.addRules(com.claims.proto.policy.v1.CoverageRule.newBuilder()
                    .setTreatmentCategory(r.getTreatmentCategory().name()).setTreatmentLabel(r.getTreatmentCategory().label())
                    .setCovered(r.isCovered())
                    .setSubLimit(r.isCovered() && r.getSubLimit() != null ? r.getSubLimit().toPlainString() : "")
                    .setWaitingPeriodDays(r.getWaitingPeriodDays()));
        }
        return b.build();
    }

    public BalanceDetails balance(PolicyView v) {
        Policy p = v.policy();
        PolicyBalance bal = v.balance();
        return BalanceDetails.newBuilder().setPolicyNo(p.getPolicyNo())
                .setSumInsured(p.getSumInsured().toPlainString())
                .setUtilised(bal.getUtilised().toPlainString())
                .setReserved(bal.getReserved().toPlainString())
                .setRemaining(bal.remaining(p.getSumInsured()).toPlainString())
                .build();
    }

    static String rupees(BigDecimal v) {
        return Rupees.format(v);
    }
}
