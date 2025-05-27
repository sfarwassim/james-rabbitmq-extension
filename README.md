# Apache James Custom RabbitMQ Integration Extension

This extension integrates Apache James with RabbitMQ to process mailbox operations based on messages received from a queue.

## Prerequisites

-   Java 17
-   Apache James 3.8.2
-   RabbitMQ Server

## Tech Stack Used

-   Java 17
-   RabbitMQ Client (amqp-client)
-   Jackson (for JSON processing)
-   Apache James Mailbox API

## Configuration

The RabbitMQ connection details are currently hardcoded in `RabbitMqIntegrationService.java`.
These include:
-   RabbitMQ Host (default: "rabbitmq" - as per your docker-compose service name)
-   RabbitMQ Port (default: 5672)
-   Incoming Queue Name (default: "james_action_queue")
-   Outgoing Exchange Name (default: "james_status_exchange")
-   Outgoing Routing Key (default: "status.update")

These should be externalized to a configuration file for production deployments (e.g., via `james-extensions.properties` or a custom properties file).

## Building

This project uses Maven. To build the shaded JAR:

```bash
mvn clean package