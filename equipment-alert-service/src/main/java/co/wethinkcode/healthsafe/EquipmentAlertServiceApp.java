package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;

public class EquipmentAlertServiceApp {

    public static void main(String[] args) {
        AlertStore store = new AlertStore(Path.of(
                System.getenv().getOrDefault("EQUIPMENT_ALERT_LOG", "equipment-alerts.log")));
        EquipmentQueueConsumer consumer = new EquipmentQueueConsumer(store);
        consumer.start();
        Javalin app = Javalin.create().start(7034);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/equipment-alerts", ctx -> ctx.json(store.alerts()));
    }

    public record EquipmentAlert(String wardId, String department, String equipment,
                                 String details, String reportedAt) { }

    /** Persists the alert before the JMS message is acknowledged, allowing at-least-once delivery. */
    private static final class AlertStore {
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final Path logFile;
        private final List<EquipmentAlert> alerts = new ArrayList<>();

        private AlertStore(Path logFile) {
            this.logFile = logFile;
            restore();
        }

        private synchronized void save(EquipmentAlert alert) throws IOException {
            Files.writeString(logFile, objectMapper.writeValueAsString(alert) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            alerts.add(alert);
        }

        private synchronized List<EquipmentAlert> alerts() {
            return List.copyOf(alerts);
        }

        private void restore() {
            if (!Files.exists(logFile)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(logFile)) {
                    if (!line.isBlank()) {
                        alerts.add(objectMapper.readValue(line, EquipmentAlert.class));
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not restore equipment alerts", exception);
            }
        }
    }

    private static final class EquipmentQueueConsumer {
        private final AlertStore store;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "equipment-alert-queue-consumer");
            thread.setDaemon(true);
            return thread;
        });

        private EquipmentQueueConsumer(AlertStore store) {
            this.store = store;
        }

        private void start() {
            worker.submit(this::consume);
        }

        private void consume() {
            while (running.get()) {
                try (Connection connection = new ActiveMQConnectionFactory(MqConfig.BROKER_URL).createConnection();
                     Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                     MessageConsumer consumer = session.createConsumer(session.createQueue(MqConfig.QUEUE))) {
                    consumer.setMessageListener(this::process);
                    connection.start();
                    while (running.get()) {
                        Thread.sleep(500);
                    }
                } catch (Exception exception) {
                    if (running.get()) {
                        System.err.println("Waiting for equipment-failure queue: " + exception.getMessage());
                        pauseBeforeRetry();
                    }
                }
            }
        }

        private void process(Message message) {
            try {
                if (!(message instanceof TextMessage textMessage)) {
                    throw new IllegalArgumentException("Equipment alert was not a text message");
                }
                EquipmentAlert alert = objectMapper.readValue(textMessage.getText(), EquipmentAlert.class);
                store.save(alert);
                message.acknowledge();
            } catch (Exception exception) {
                // No acknowledgement: ActiveMQ retains the message for redelivery.
                throw new IllegalStateException("Could not persist equipment alert", exception);
            }
        }

        private void pauseBeforeRetry() {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                running.set(false);
            }
        }
    }
}
