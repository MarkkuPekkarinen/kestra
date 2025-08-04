package io.kestra.controller.grpc.client;

import io.kestra.controller.grpc.LivenessControllerServiceGrpc;
import io.kestra.controller.grpc.LivenessControllerServiceGrpc.LivenessControllerServiceBlockingStub;
import io.kestra.controller.GrpcChannelProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

@Factory
public class GrpcClientBeanFactory {

    @Bean
    @Singleton
    public LivenessControllerServiceBlockingStub workerServiceStub(GrpcChannelProvider grpcChannelProvider) {
        return LivenessControllerServiceGrpc.newBlockingStub(grpcChannelProvider.createOrGetDefault());
    }
}
