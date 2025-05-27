package com.integration.james.module;

import com.google.inject.*;
import com.integration.james.services.MailboxActionService;
import com.integration.james.services.impl.MailboxActionServiceImpl;
import com.integration.james.services.RabbitMqIntegrationService;

import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.lifecycle.api.Startable;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;


public class CustomIntegrationModule extends PrivateModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomIntegrationModule.class);
    private static final String RABBITMQ_PROPERTIES_FILE = "rabbitmq.properties";

    @Override
    protected void configure() {
        LOGGER.info("Loading CustomIntegrationModule for RabbitMQ integration...");

        bind(MailboxActionService.class).to(MailboxActionServiceImpl.class).in(Singleton.class);
        bind(RabbitMqIntegrationService.class).asEagerSingleton();

        //expose(RabbitMqIntegrationService.class);

        // This ensures its init() method is called at server startup.
        //Multibinder<InitializationOperation> initializationOperations = Multibinder.newSetBinder(exposedBinder(), InitializationOperation.class);
       // initializationOperations.addBinding().to(RabbitMqInitOp.class);


        // Load and bind RabbitMQ configuration properties
        try {
            Properties rabbitMqProperties = loadRabbitMqProperties();
            Names.bindProperties(binder(), rabbitMqProperties);
            LOGGER.info("RabbitMQ configuration properties loaded and bound.");
        } catch (IOException e) {
            LOGGER.error("Failed to load RabbitMQ configuration from {}. Service may not start correctly.", RABBITMQ_PROPERTIES_FILE, e);
            // Optionally, rethrow as a ConfigurationException or allow James to start with defaults/errors
            // For this example, we log the error and continue, which might lead to RabbitMqIntegrationService failing to init.
            // A more robust approach would be to fail fast if essential configuration is missing.
            // addError(e); // This would prevent Guice injector creation if the file is critical
        }
    }



//    @Singleton
//    public static class RabbitMqInitOp implements InitializationOperation {
//        private final RabbitMqIntegrationService service;
//
//        @Inject
//        RabbitMqInitOp(RabbitMqIntegrationService service) {
//            this.service = service;
//        }
//
//        @Override
//        public void initModule() throws Exception {
//            service.init();
//        }
//
//        @Override
//        public Class<? extends Startable> forClass() {
//            return RabbitMqIntegrationService.class;
//        }
//    }


//    @Singleton
//    public static class RabbitModuleInitializationOperation implements InitializationOperation {
//            private  final RabbitMqIntegrationService rabbitMqIntegrationService;
//
//            @Inject
//             public RabbitModuleInitializationOperation(RabbitMqIntegrationService rabbitMqIntegrationService) {
//                this.rabbitMqIntegrationService = rabbitMqIntegrationService;
//
//            }
//
//            public void initModule() throws Exception {
//                this.rabbitMqIntegrationService.init();
//
//            }
//
//        public Class<? extends Startable> forClass() {
//            return RabbitMqIntegrationService.class;
//        }
//
//
//
//
//    }


    private Properties loadRabbitMqProperties() throws IOException {
        Properties properties = new Properties();
        // Attempt to load from classpath first (e.g., if bundled in JAR)
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(RABBITMQ_PROPERTIES_FILE);
        if (inputStream!= null) {
            try (InputStream is = inputStream) {
                properties.load(is);
                LOGGER.info("Loaded {} from classpath.", RABBITMQ_PROPERTIES_FILE);
                return properties;
            }
        }

        // If not found in classpath, try loading from James 'conf' directory
        // The path to the conf directory needs to be determined.
        // Assuming James sets a system property or provides a service to get this path.
        // For simplicity, let's assume it's in 'conf/' relative to working directory if not in classpath.
        // A more robust way for extensions is to rely on James's configuration loading mechanisms if available
        // or expect the file to be in a known location like `james-server-app-x.y.z/conf/`.
        String configurationPath = System.getProperty("james.configuration.path", "conf"); // Example property
        try (FileReader reader = new FileReader(configurationPath + "/" + RABBITMQ_PROPERTIES_FILE)) {
            properties.load(reader);
            LOGGER.info("Loaded {} from file system: {}/{}", RABBITMQ_PROPERTIES_FILE, configurationPath, RABBITMQ_PROPERTIES_FILE);
        } catch (FileNotFoundException e) {
            LOGGER.warn("{} not found in classpath or filesystem ({}). Using defaults or environment variables if available.",
                    RABBITMQ_PROPERTIES_FILE, configurationPath + "/" + RABBITMQ_PROPERTIES_FILE);
            // If the file is not found, the properties object will be empty.
            // The RabbitMqIntegrationService should handle missing properties gracefully or fail.
            // This example assumes that if the file is not found, essential properties might be missing.
            throw new FileNotFoundException(RABBITMQ_PROPERTIES_FILE + " not found in classpath or filesystem. Essential configuration might be missing.");
        }
        return properties;
    }


}