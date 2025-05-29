package com.integration.james.services;

import com.integration.james.dto.IncomingMessagePayload;
import com.integration.james.dto.StatusPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.rabbitmq.client.*;
import org.apache.james.lifecycle.api.Startable;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

@Singleton
public class RabbitMqIntegrationService implements Startable, RecoveryListener, ShutdownListener {


    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqIntegrationService.class);


    private final MailboxActionService  mailboxActionService;
    private final String rabbitMqHost;
    private final int rabbitMqPort;
    private final String rabbitMqUsername;
    private final String rabbitMqPassword;
    private final String rabbitMqQueueName;
    private final String rabbitMqExchangeName;
    private final String rabbitMqRoutingKey;

    private volatile  Connection connection;
    private volatile  Channel consumerChannel;
    private volatile  Channel   publisherChannel;

    private final ObjectMapper objectMapper;
    private ExecutorService executorService; // For message consumption
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "rmq-retry"));


    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY     = Duration.ofMinutes(1);


    @Inject
    public RabbitMqIntegrationService(MailboxActionService  mailboxActionService,
                                      @Named("rabbitmq.host") String rabbitMqHost,
                                      @Named("rabbitmq.port") int rabbitMqPort,
                                      @Named("rabbitmq.username") String rabbitMqUsername,
                                      @Named("rabbitmq.password") String rabbitMqPassword,
                                      @Named("rabbitmq.queueName") String rabbitMqQueueName,
                                      @Named("rabbitmq.routingKey") String rabbitMqRoutingKey,
                                      @Named("rabbitmq.exchangeName") String rabbitMqExchangeName ) {

        this.mailboxActionService = mailboxActionService;
        this.rabbitMqHost = rabbitMqHost;
        this.rabbitMqPort = rabbitMqPort;
        this.rabbitMqUsername = rabbitMqUsername;
        this.rabbitMqPassword = rabbitMqPassword;
        this.rabbitMqQueueName = rabbitMqQueueName;
        this.rabbitMqExchangeName = rabbitMqExchangeName;
        this.rabbitMqRoutingKey = rabbitMqRoutingKey;
        this.objectMapper = new ObjectMapper();
        LOGGER.info("Constructor RabbitMqIntegrationService...");

    }


    @Inject
    public void init() {
        LOGGER.info("Initializing RabbitMqIntegrationService...");
        connectWithRetry(INITIAL_RETRY_DELAY);
    }

    /** Try to establish the connection; on failure, schedule itself again. */
    private void connectWithRetry(Duration delay) {
        scheduler.schedule(() -> {
            try {
                establishConnection();              // throws if it fails
                LOGGER.info("RabbitMQ connection established.");
            } catch (Exception e) {
                LOGGER.warn("Cannot connect to RabbitMQ ({}). Retrying in {} s",
                        e.getMessage(), delay.toSeconds());
                Duration nextDelay = delay.multipliedBy(2).compareTo(MAX_RETRY_DELAY) < 0
                        ? delay.multipliedBy(2) : MAX_RETRY_DELAY;
                connectWithRetry(nextDelay);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One-time connection & topology bootstrap – will be auto-recovered by the client. */
    private  void establishConnection() throws IOException, TimeoutException {

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMqHost);
        factory.setPort(rabbitMqPort);
        factory.setUsername(rabbitMqUsername);
        factory.setPassword(rabbitMqPassword);

        // --- explicit recovery tuning ---
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        factory.setRequestedHeartbeat(30);
        factory.setNetworkRecoveryInterval(5000);

        this.connection = factory.newConnection("james-integration");
        this.connection.addShutdownListener(this);
        ((Recoverable) this.connection).addRecoveryListener(this);

        // ------------ channels ------------
        this.consumerChannel  = connection.createChannel();
        this.publisherChannel = connection.createChannel();
        publisherChannel.confirmSelect();

        // declare infra idempotently on BOTH channels
        declareInfra(consumerChannel);
        declareInfra(publisherChannel);

        // QoS & consumer
        consumerChannel.basicQos(25);
        consumerChannel.basicConsume(
                rabbitMqQueueName,
                false,
                this::handleDelivery,
                consumerTag -> LOGGER.warn("Consumer {} cancelled", consumerTag));
    }

    private void declareInfra(Channel ch) throws IOException {
        ch.queueDeclare(rabbitMqQueueName, true, false, false, null);
        ch.exchangeDeclare(rabbitMqExchangeName, BuiltinExchangeType.DIRECT, true);
    }

    // ---------- delivery handler ----------
    private void handleDelivery(String tag, Delivery delivery) {
        String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
        boolean ok = false;
        String hash = null;

        try {
            IncomingMessagePayload p = objectMapper.readValue(body, IncomingMessagePayload.class);
            hash = p.getHashID();
            LOGGER.info("Processing action '{}' for hashID '{}'", p.getAction(), hash);
            ok = mailboxActionService.processMessageAction(p);
        } catch (Exception ex) {
            LOGGER.error("Error while processing message: {}", ex.getMessage(), ex);
        } finally {
            sendStatus(hash, ok ? "success" : "failed");
            try {
                consumerChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            } catch (IOException io) {
                LOGGER.error("Ack failed", io);
            }
        }
    }


    // ---------- result publisher ----------
    private void sendStatus(String hashID, String status) {
        if (hashID == null)  {
            LOGGER.warn("Cannot send status update because hashID is unknown (likely due to parsing error of incoming message).");
            return;
        }
        try {
            StatusPayload statusPayload = new StatusPayload(hashID, status);
            String jsonStatus = objectMapper.writeValueAsString(statusPayload);

            if (publisherChannel != null && publisherChannel.isOpen()) {
                publisherChannel.basicPublish(rabbitMqExchangeName, rabbitMqRoutingKey, null, jsonStatus.getBytes(StandardCharsets.UTF_8));
                LOGGER.info("Published status for hashID '{}': {}", hashID, jsonStatus);
            }

            publisherChannel.waitForConfirmsOrDie(5_000);
        } catch (Exception e) {
            LOGGER.error("Status publish failed – will retry on next recovery: {}", e.getMessage());
            // you may push the payload in an in-memory queue here and flush on recovery()
        }
    }

    // ---------- RecoveryListener ----------
    @Override public void handleRecovery(Recoverable r) {
        LOGGER.info("RabbitMQ connection recovered – flushing pending status messages");
        // if you queued failed publishes, flush them here
    }
    @Override public void handleRecoveryStarted(Recoverable r) { }

    // ---------- ShutdownListener ----------
    @Override public void shutdownCompleted(ShutdownSignalException cause) {
        LOGGER.warn("RabbitMQ shutdown: {}", cause.getMessage());
    }

    @PreDestroy
    public void dispose() throws IOException, TimeoutException {
        scheduler.shutdownNow();
        closeSafely(publisherChannel);
        closeSafely(consumerChannel);
        closeSafely(connection);
    }

    private void closeSafely(AutoCloseable c) { /* helper intentionally omitted */ }



}