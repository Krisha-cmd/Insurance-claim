package com.claims.approval.grpc;

import io.grpc.ManagedChannel;

/** Creates channels from gRPC targets (e.g. "dns:///payment:9090"). Tests swap in in-process channels. */
@FunctionalInterface
public interface GrpcChannelFactory {

    ManagedChannel create(String target);
}
