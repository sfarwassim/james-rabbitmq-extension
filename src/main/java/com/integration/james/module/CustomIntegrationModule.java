package com.integration.james.module;

import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.integration.james.services.MailboxActionService;
import com.integration.james.services.impl.MailboxActionServiceImpl;
import com.integration.james.services.RabbitMqIntegrationService;

import com.google.inject.name.Names;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;


public class CustomIntegrationModule extends PrivateModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomIntegrationModule.class);
    private static final String RABBITMQ_PROPERTIES_FILE = "rabbitmq.properties";

    @Override
    protected void configure() {
        LOGGER.info("Loading CustomIntegrationModule for RabbitMQ integration...");

        bind(MailboxActionService.class).to(MailboxActionServiceImpl.class).in(Singleton.class);
        bind(RabbitMqIntegrationService.class).asEagerSingleton();


        try {
            Properties rabbitMqProperties = loadRabbitMqProperties();
            Names.bindProperties(binder(), rabbitMqProperties);
            LOGGER.info("RabbitMQ configuration properties successfully loaded and bound from '{}'.", RABBITMQ_PROPERTIES_FILE);
        } catch (IOException e) {
            // If configuration loading fails, log a significant error.
            // Guice will typically abort injector creation if a module's configure() method throws an exception,
            // preventing the application/plugin from starting in a broken state.
            LOGGER.error("Failed to load RabbitMQ configuration from '{}'. The integration service will likely be non-functional.",
                    RABBITMQ_PROPERTIES_FILE, e);

        }
    }


    /**
     * Loads RabbitMQ properties from the James configuration directory.
     * It expects the file to be located at {@code <james.configuration.path>/rabbitmq.properties}
     * or {@code conf/rabbitmq.properties} if the system property is not set.
     *
     * @return The loaded Properties object.
     * @throws IOException if the file cannot be found or read.
     */
    private Properties loadRabbitMqProperties() throws IOException {
        Properties properties = new Properties();

        // Determine the configuration directory.
        // Defaults to "conf" relative to the James server's working directory
        // if the "james.configuration.path" system property is not explicitly set.
        String configurationDirectory = System.getProperty("james.configuration.path", "conf");
        Path propertiesFilePath = Paths.get(configurationDirectory, RABBITMQ_PROPERTIES_FILE);

        LOGGER.info("Attempting to load RabbitMQ configuration from: {}", propertiesFilePath.toAbsolutePath());

        try (InputStream inputStream = Files.newInputStream(propertiesFilePath)) {
            properties.load(inputStream);
            LOGGER.info("Successfully loaded RabbitMQ configuration from: {}", propertiesFilePath.toAbsolutePath());
        } catch (NoSuchFileException e) {
            // This specific exception indicates the file was not found.
            LOGGER.error("CRITICAL: RabbitMQ configuration file NOT FOUND at: {}. This file is required for the module to operate.",
                    propertiesFilePath.toAbsolutePath());
            // Re-throw as FileNotFoundException for conventional error handling if specific catch blocks for it exist upstream,
            // or simply let NoSuchFileException (which is an IOException) propagate.
            throw new FileNotFoundException("Required RabbitMQ configuration file ('" + RABBITMQ_PROPERTIES_FILE + "') not found at " + propertiesFilePath.toAbsolutePath());
        } catch (IOException e) {
            // Handles other I/O errors during reading the file.
            LOGGER.error("CRITICAL: An error occurred while reading the RabbitMQ configuration file from: {}.",
                    propertiesFilePath.toAbsolutePath(), e);
            throw e; // Re-throw the original IOException
        }
        return properties;
    }

}


