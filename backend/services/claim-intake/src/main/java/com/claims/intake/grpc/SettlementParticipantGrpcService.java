package com.claims.intake.grpc;

import com.claims.intake.service.ClaimSettlementParticipant;
import com.claims.participant.ParticipantGrpcAdapter;
import org.springframework.stereotype.Component;

/** SettlementParticipant (claims/settlement/v1/participant.proto), called by Approval, the coordinator. */
@Component
public class SettlementParticipantGrpcService extends ParticipantGrpcAdapter {

    public SettlementParticipantGrpcService(ClaimSettlementParticipant participant) {
        super(participant);
    }
}
