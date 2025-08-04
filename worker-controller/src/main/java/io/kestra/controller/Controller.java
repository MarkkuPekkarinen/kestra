package io.kestra.controller;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.kestra.controller.config.GrpcChannelConfiguration;
import io.kestra.controller.grpc.server.GrpcLivenessControllerService;
import io.kestra.controller.grpc.server.GrpcWorkerControllerService;
import io.kestra.core.server.AbstractService;
import io.kestra.core.server.Service;
import io.kestra.core.server.ServiceStateChangeEvent;
import io.kestra.core.server.ServiceType;
import io.kestra.core.services.MaintenanceService;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * TODO
 */
@Context
@Requires(property = "kestra.server-type", pattern = "(CONTROLLER|STANDALONE)")
public class Controller extends AbstractService implements Service {

    private static final Logger log = LoggerFactory.getLogger(Controller.class);

    private Server server;

    private final GrpcWorkerControllerService workerControllerService;
    private final GrpcLivenessControllerService livenessControllerService;
    private final GrpcChannelConfiguration grpcChannelConfiguration;

    @Inject
    public Controller(
        GrpcWorkerControllerService workerControllerService,
        GrpcLivenessControllerService livenessControllerService,
        GrpcChannelConfiguration grpcChannelConfiguration,
        ApplicationEventPublisher<ServiceStateChangeEvent> eventPublisher) {
        super(ServiceType.CONTROLLER, eventPublisher);
        this.workerControllerService = workerControllerService;
        this.livenessControllerService = livenessControllerService;
        this.grpcChannelConfiguration = grpcChannelConfiguration;
    }

    @PostConstruct
    public void start() throws IOException {
        if (getState() != ServiceState.CREATED) {
            throw new IllegalStateException("Controller is already started or stopped");
        }

        log.info("Starting Controller");
        /* The port on which the server should run */
        int port = grpcChannelConfiguration.port();
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(workerControllerService)
            .addService(livenessControllerService)
            .build()
            .start();
        log.info("Controller started, listening on {}", port);

        setState(ServiceState.RUNNING);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ServiceState doStop() throws InterruptedException {
        if (server != null && !server.isTerminated()) {
            shutdownServerAndWait();
        }
        return ServiceState.TERMINATED_GRACEFULLY;
    }

    private void shutdownServerAndWait() throws InterruptedException {
        server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
}
