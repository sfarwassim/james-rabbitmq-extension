package com.integration.james.services;

import com.integration.james.dto.IncomingMessagePayload;
import com.integration.james.dto.StatusPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.james.lifecycle.api.Startable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

@Singleton
public class RabbitMqIntegrationService implements Startable {


    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqIntegrationService.class);


    private final MailboxActionService  mailboxActionService;
    private final String rabbitMqHost;
    private final int rabbitMqPort;
    private final String rabbitMqUsername;
    private final String rabbitMqPassword;
    private final String rabbitMqQueueName;
    private final String rabbitMqExchangeName;
    private final String rabbitMqRoutingKey;

    private Connection connection;
    private Channel channel;
    private final ObjectMapper objectMapper;
    private ExecutorService executorService; // For message consumption


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
    public void init() throws Exception {
        LOGGER.info("Initializing RabbitMqIntegrationService...");
        executorService = Executors.newSingleThreadExecutor(); // Or a fixed-size pool if expecting high throughput
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMqHost);
        factory.setPort(rabbitMqPort);
        factory.setUsername(rabbitMqUsername);
        factory.setPassword(rabbitMqPassword);


        try {
            connection = factory.newConnection(executorService); // Share executor with connection
            channel = connection.createChannel();
            // Ensure queue exists, or declare it if it's meant to be durable
            channel.queueDeclare(rabbitMqQueueName, true, false, false, null);
            LOGGER.info("Successfully connected to RabbitMQ and channel opened. Queue: {}", rabbitMqQueueName);

            // Declare output exchange (idempotent)
            channel.exchangeDeclare(rabbitMqExchangeName, "direct", true); // Or "topic" etc. based on needs
            LOGGER.info("Declared exchange: {}", rabbitMqExchangeName);

            LOGGER.info("RabbitMQ Integration Service started. Waiting for messages on '{}'", rabbitMqQueueName);

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    String messageBody = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    LOGGER.debug("Received message: {}", messageBody);
                    IncomingMessagePayload payload = null;
                    boolean processingSuccess = false;
                    String hashID = null;

                    try {
                        payload = objectMapper.readValue(messageBody, IncomingMessagePayload.class);
                        hashID = payload.getHashID(); // Get hashID early for logging/status
                        LOGGER.info("Processing action '{}' for hashID '{}'", payload.getAction(), hashID);
                        processingSuccess = mailboxActionService.processMessageAction(payload);
                    } catch (JsonProcessingException e) {
                        LOGGER.error("Failed to parse JSON payload: {}. Error: {}", messageBody, e.getMessage());
                        // hashID might be null here if parsing fails very early
                        // Depending on requirements, you might try to extract hashID via regex or similar if critical
                    } catch (Exception e) {
                        LOGGER.error("Unexpected error processing message for hashID '{}': {}", hashID, e.getMessage(), e);
                    } finally {
                        sendStatus(hashID, processingSuccess ? "success" : "failed");
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        LOGGER.debug("Acknowledged message with delivery tag: {}", delivery.getEnvelope().getDeliveryTag());
                    }
                };

                CancelCallback cancelCallback = consumerTag -> {
                    LOGGER.warn("Consumer {} was cancelled", consumerTag);
                    // Handle cancellation, e.g., by trying to restart consumption or logging an alert.
                };


                // Start consuming
                channel.basicConsume(rabbitMqQueueName, false, deliverCallback, cancelCallback); // Manual ack

            } catch (IOException | TimeoutException e) {
                LOGGER.error("RabbitMQ connection/setup failed: {}", e.getMessage(), e);
                // Clean up resources if initialization fails partially
                if (channel!= null && channel.isOpen()) {
                    try {
                        channel.close();
                    } catch (IOException | TimeoutException ex) {
                        LOGGER.error("Error closing channel during failed init", ex);
                    }
                }
                if (connection!= null && connection.isOpen()) {
                    try {
                        connection.close();
                    } catch (IOException ex) {
                        LOGGER.error("Error closing connection during failed init", ex);
                }
            }
            if (executorService!= null &&!executorService.isShutdown()) {
                executorService.shutdownNow();
            }
            throw new Exception("Failed to initialize RabbitMqIntegrationService", e);
        }


    }

    private void sendStatus(String hashID, String status) {
        if (hashID == null) {
            LOGGER.warn("Cannot send status update because hashID is unknown (likely due to parsing error of incoming message).");
            return;
        }
        try {
            StatusPayload statusPayload = new StatusPayload(hashID, status);
            String jsonStatus = objectMapper.writeValueAsString(statusPayload);
            if (channel != null && channel.isOpen()) {
                channel.basicPublish(rabbitMqExchangeName, rabbitMqRoutingKey, null, jsonStatus.getBytes(StandardCharsets.UTF_8));
                LOGGER.info("Published status for hashID '{}': {}", hashID, jsonStatus);
            } else {
                LOGGER.error("Cannot send status for hashID '{}', channel is not open.", hashID);
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to serialize StatusPayload for hashID '{}': {}", hashID, e.getMessage(), e);
        } catch (IOException e) {
            LOGGER.error("Failed to publish status to RabbitMQ for hashID '{}': {}", hashID, e.getMessage(), e);
        }
    }

    @PreDestroy
    public void dispose() {
        LOGGER.info("Disposing RabbitMqIntegrationService...");
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
                LOGGER.info("RabbitMQ channel closed.");
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
                LOGGER.info("RabbitMQ connection closed.");
            }
        } catch (IOException | TimeoutException e) {
            LOGGER.error("Error closing RabbitMQ resources: {}", e.getMessage(), e);
        } finally {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                LOGGER.info("RabbitMQ Integration processor thread pool shutdown.");
            }
        }
    }
}