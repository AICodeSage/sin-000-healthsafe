package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.wethinkcode.healthsafe.mq.MqConfig;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.ActiveMQConnectionFactory;

public class StaffingServiceApp {
    private static final String DEFAULT_WARD_SERVICE_URL = "http://localhost:7031";
    private static final String DEFAULT_ALERT_LEVEL_SERVICE_URL = "http://localhost:7032";

    public static void main(String[] args) {
        HospitalClient hospital = new HospitalClient(
                System.getenv().getOrDefault("WARD_SERVICE_URL", DEFAULT_WARD_SERVICE_URL),
                System.getenv().getOrDefault("ALERT_LEVEL_SERVICE_URL", DEFAULT_ALERT_LEVEL_SERVICE_URL));
        StaffingEventPublisher eventPublisher = new StaffingEventPublisher();
        Javalin app = Javalin.create().start(7033);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/staffing-schedule/{wardId}", ctx -> {
            try {
                Ward ward = hospital.ward(ctx.pathParam("wardId"));
                if (ward == null) {
                    ctx.status(404).json(new ErrorResponse("Ward not found: " + ctx.pathParam("wardId")));
                    return;
                }
                AlertLevel alertLevel = hospital.alertLevel();
                Schedule schedule = Schedule.create(ward, alertLevel.level());
                boolean published = eventPublisher.publish(schedule);
                ctx.json(new ScheduleResponse(schedule, published));
            } catch (DownstreamUnavailableException exception) {
                serviceUnavailable(ctx, exception.service());
            }
        });
    }

    private static void serviceUnavailable(Context ctx, String service) {
        ctx.status(503).json(new ErrorResponse(service + " is unavailable"));
    }

    private record ErrorResponse(String error) { }

    public record Ward(String wardId, String wing, String department, Integer bedsAvailable, String notes) { }

    public record AlertLevel(int level) { }

    public record Schedule(String wardId, String department, int alertLevel,
                           int requiredDoctors, List<String> onCallRoles) {
        static Schedule create(Ward ward, int alertLevel) {
            List<String> roles = new ArrayList<>(List.of("Ward attending"));
            if (alertLevel >= 3) {
                roles.add("Emergency physician");
            }
            if (alertLevel >= 6) {
                roles.add("Critical care specialist");
            }
            if (alertLevel == 8) {
                roles.add("Code Blue support physician");
            }
            return new Schedule(ward.wardId(), ward.department(), alertLevel, roles.size(), List.copyOf(roles));
        }
    }

    public record ScheduleResponse(Schedule schedule, boolean staffingEventPublished) { }

    private static final class StaffingEventPublisher {
        private final ObjectMapper objectMapper = new ObjectMapper();

        private boolean publish(Schedule schedule) {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
            try (Connection connection = factory.createConnection();
                 Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 MessageProducer producer = session.createProducer(session.createTopic(MqConfig.TOPIC))) {
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                TextMessage message = session.createTextMessage(objectMapper.writeValueAsString(schedule));
                producer.send(message);
                return true;
            } catch (Exception exception) {
                System.err.println("Unable to publish staffing event: " + exception.getMessage());
                return false;
            }
        }
    }

    private static final class HospitalClient {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final String wardServiceUrl;
        private final String alertLevelServiceUrl;

        private HospitalClient(String wardServiceUrl, String alertLevelServiceUrl) {
            this.wardServiceUrl = stripTrailingSlash(wardServiceUrl);
            this.alertLevelServiceUrl = stripTrailingSlash(alertLevelServiceUrl);
        }

        private Ward ward(String wardId) throws DownstreamUnavailableException {
            HttpResponse<String> response = get(wardServiceUrl + "/wards/"
                    + URLEncoder.encode(wardId, StandardCharsets.UTF_8), "Ward service");
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new DownstreamUnavailableException("Ward service");
            }
            return read(response.body(), Ward.class, "Ward service");
        }

        private AlertLevel alertLevel() throws DownstreamUnavailableException {
            HttpResponse<String> response = get(alertLevelServiceUrl + "/alert-level", "Alert-level service");
            if (response.statusCode() != 200) {
                throw new DownstreamUnavailableException("Alert-level service");
            }
            AlertLevel level = read(response.body(), AlertLevel.class, "Alert-level service");
            if (level.level() < 0 || level.level() > 8) {
                throw new DownstreamUnavailableException("Alert-level service");
            }
            return level;
        }

        private <T> T read(String body, Class<T> type, String service) throws DownstreamUnavailableException {
            try {
                return objectMapper.readValue(body, type);
            } catch (IOException exception) {
                throw new DownstreamUnavailableException(service);
            }
        }

        private HttpResponse<String> get(String url, String service) throws DownstreamUnavailableException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException exception) {
                throw new DownstreamUnavailableException(service);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DownstreamUnavailableException(service);
            }
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    private static final class DownstreamUnavailableException extends Exception {
        private final String service;

        private DownstreamUnavailableException(String service) {
            this.service = service;
        }

        private String service() {
            return service;
        }
    }
}
