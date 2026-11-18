package com.claims.payment.grpc;

import com.claims.participant.ParticipantGrpcAdapter;
import com.claims.payment.service.PaymentParticipant;
import org.springframework.stereotype.Component;

/** SettlementParticipant (claims/settlement/v1/participant.proto), called by Approval, the coordinator. */
@Component
public class PaymentParticipantGrpcService extends ParticipantGrpcAdapter {

    public PaymentParticipantGrpcService(PaymentParticipant participant) {
        super(participant);
    }
}
