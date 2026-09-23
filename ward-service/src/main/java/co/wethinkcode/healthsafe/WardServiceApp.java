package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public class WardServiceApp {
    private static final String DEFAULT_INGESTION_URL = "http://localhost:7030";

    public static void main(String[] args) {
        WardDirectoryClient directory = new WardDirectoryClient(
                System.getenv().getOrDefault("INGESTION_SERVICE_URL", DEFAULT_INGESTION_URL));
        Javalin app = Javalin.create().start(7031);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/wards", ctx -> {
            try {
                ctx.json(directory.wards());
            } catch (DownstreamUnavailableException exception) {
                unavailable(ctx, exception);
            }
        });
        app.get("/wards/{id}", ctx -> {
            try {
                WardRecord ward = directory.ward(ctx.pathParam("id"));
                if (ward == null) {
                    ctx.status(404).json(new ErrorResponse("Ward not found: " + ctx.pathParam("id")));
                    return;
                }
                ctx.json(ward);
            } catch (DownstreamUnavailableException exception) {
                unavailable(ctx, exception);
            }
        });
        app.get("/departments", ctx -> {
            try {
                List<String> departments = directory.wards().stream()
                        .map(WardRecord::department)
                        .filter(department -> department != null)
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList();
                ctx.json(departments);
            } catch (DownstreamUnavailableException exception) {
                unavailable(ctx, exception);
            }
        });
    }

    private static void unavailable(io.javalin.http.Context ctx, DownstreamUnavailableException exception) {
        ctx.status(503).json(new ErrorResponse("Ingestion service is unavailable"));
    }

    private record ErrorResponse(String error) { }

    private record WardRecord(String wardId, String wing, String department,
                              Integer bedsAvailable, String notes) { }

    private static final class WardDirectoryClient {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final String ingestionUrl;

        private WardDirectoryClient(String ingestionUrl) {
            this.ingestionUrl = stripTrailingSlash(ingestionUrl);
        }

        private List<WardRecord> wards() throws DownstreamUnavailableException {
            HttpResponse<String> response = request("/wards");
            if (response.statusCode() != 200) {
                throw new DownstreamUnavailableException();
            }
            try {
                return objectMapper.readValue(response.body(), new TypeReference<>() { });
            } catch (IOException exception) {
                throw new DownstreamUnavailableException();
            }
        }

        private WardRecord ward(String wardId) throws DownstreamUnavailableException {
            HttpResponse<String> response = request("/wards/" + wardId);
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new DownstreamUnavailableException();
            }
            try {
                return objectMapper.readValue(response.body(), WardRecord.class);
            } catch (IOException exception) {
                throw new DownstreamUnavailableException();
            }
        }

        private HttpResponse<String> request(String path) throws DownstreamUnavailableException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ingestionUrl + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException exception) {
                throw new DownstreamUnavailableException();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DownstreamUnavailableException();
            }
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    private static final class DownstreamUnavailableException extends Exception { }
}
