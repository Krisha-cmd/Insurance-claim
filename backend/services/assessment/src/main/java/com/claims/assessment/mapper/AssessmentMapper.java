package com.claims.assessment.mapper;

import com.claims.assessment.api.dto.AssessmentResponse;
import com.claims.assessment.api.dto.BreakdownLine;
import com.claims.assessment.domain.Assessment;
import com.claims.assessment.service.AssessmentCalculator;
import com.claims.proto.assessment.v1.AssessReply;
import com.claims.proto.assessment.v1.AssessmentLine;
import com.claims.proto.assessment.v1.FinalAmountReply;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AssessmentMapper {

    /** The breakdown, rebuilt from the stored steps with the same labels the calculator produces. */
    public List<BreakdownLine> lines(Assessment a) {
        return AssessmentCalculator.breakdown(a.getTreatmentCategory(), a.getClaimedAmount(), a.getSubLimitApplied(),
                a.getAfterCap(), a.getDeductible(), a.getAfterDeductible(), a.getCopayPct(), a.getCopayAmount(),
                a.getRemainingBalance(), a.balanceCut(), a.getProposedAmount());
    }

    public AssessmentResponse response(Assessment a) {
        return new AssessmentResponse(
                a.getClaimId(), a.getPolicyNo(), a.getTreatmentCategory(),
                a.getClaimedAmount(), a.getSubLimitApplied(), a.getAfterCap(), a.getDeductible(),
                a.getAfterDeductible(), a.getCopayPct(), a.getCopayAmount(), a.getRemainingBalance(),
                a.getProposedAmount(), a.getFinalAmount(), a.getFinalAmount(), a.isAdjusted(), a.getCurrency(),
                lines(a),
                a.getAdjustments().stream().map(x -> new AssessmentResponse.Adjustment(
                        x.getBeforeAmount(), x.getAfterAmount(), x.getComment(),
                        new AssessmentResponse.Actor(x.getAdjusterId(), x.getAdjusterName(), "ADJUSTER"),
                        x.getAdjustedAt())).toList(),
                a.getCreatedAt(), a.getUpdatedAt());
    }

    /** {@code proposed_amount} in the reply is the amount in force, so Claim Intake never undoes an adjustment. */
    public AssessReply assessReply(Assessment a) {
        return AssessReply.newBuilder()
                .setProposedAmount(a.getFinalAmount().toPlainString())
                .setCurrency(a.getCurrency())
                .addAllLines(lines(a).stream().map(l -> AssessmentLine.newBuilder()
                        .setLabel(l.label()).setAmount(l.amount().toPlainString()).build()).toList())
                .build();
    }

    public FinalAmountReply finalAmount(Assessment a) {
        return FinalAmountReply.newBuilder()
                .setClaimId(a.getClaimId().toString())
                .setAmount(a.getFinalAmount().toPlainString())
                .setCurrency(a.getCurrency())
                .build();
    }
}
