package com.claims.approval.grpc;

import com.claims.approval.config.CoordinatorProperties;
import com.claims.proto.settlement.v1.SettlementParticipantGrpc;
import io.grpc.ManagedChannel;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** One client per entry of {@code claims.coordinator.participants}, in configuration order. */
@Component
public class ParticipantClientRegistry {

    private final Map<String, ParticipantClient> clients = new LinkedHashMap<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    public ParticipantClientRegistry(CoordinatorProperties props, GrpcChannelFactory channelFactory) {
        for (CoordinatorProperties.Participant p : props.participants()) {
            if (clients.containsKey(p.name())) {
                throw new IllegalStateException("Duplicate 3PC participant name: " + p.name());
            }
            ManagedChannel channel = channelFactory.create(p.target());
            channels.add(channel);
            clients.put(p.name(), new ParticipantClient(p.name(), SettlementParticipantGrpc.newBlockingStub(channel),
                    props.phaseTimeout()));
        }
    }

    public List<ParticipantClient> all() {
        return List.copyOf(clients.values());
    }

    public Optional<ParticipantClient> get(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    /** Position in the configuration, used to list participants in a stable order. */
    public int order(String name) {
        int i = 0;
        for (String n : clients.keySet()) {
            if (n.equals(name)) return i;
            i++;
        }
        return Integer.MAX_VALUE;
    }

    @PreDestroy
    void close() throws InterruptedException {
        for (ManagedChannel c : channels) c.shutdown();
        for (ManagedChannel c : channels) c.awaitTermination(2, TimeUnit.SECONDS);
    }
}
