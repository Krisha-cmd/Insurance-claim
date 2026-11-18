package com.claims.policy.grpc;

import com.claims.participant.ParticipantGrpcAdapter;
import com.claims.policy.service.LimitReservationParticipant;
import org.springframework.stereotype.Component;

/** SettlementParticipant (claims/settlement/v1/participant.proto), called by Approval, the coordinator. */
@Component
public class PolicyParticipantGrpcService extends ParticipantGrpcAdapter {

    public PolicyParticipantGrpcService(LimitReservationParticipant participant) {
        super(participant);
    }
}
