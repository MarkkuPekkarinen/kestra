package io.kestra.worker;

import io.kestra.controller.GrpcChannelProvider;
import io.kestra.controller.grpc.WorkerControllerServiceGrpc;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

@Factory
public class BeanFactory {

    @Bean
    @Singleton
    public WorkerControllerServiceGrpc.WorkerControllerServiceBlockingStub blockingWorkerServiceStub(GrpcChannelProvider grpcChannelProvider) {
        return WorkerControllerServiceGrpc.newBlockingStub(grpcChannelProvider.createOrGetDefault());
    }

    @Bean
    @Singleton
    public WorkerControllerServiceGrpc.WorkerControllerServiceStub asyncWorkerServiceStub(GrpcChannelProvider grpcChannelProvider) {
        return WorkerControllerServiceGrpc.newStub(grpcChannelProvider.createOrGetDefault());
    }
}
