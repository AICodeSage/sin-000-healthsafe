package co.wethinkcode.healthsafe;

import io.javalin.Javalin;
import java.util.List;

public class IngestionServiceApp {

    public static void main(String[] args) {
        List<WardRecord> wards = WardCsvLoader.load();
        Javalin app = Javalin.create().start(7030);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/wards", ctx -> ctx.json(wards));
        app.get("/wards/{id}", ctx -> {
            String wardId = WardCsvLoader.normalizeWardId(ctx.pathParam("id"));
            WardRecord ward = wards.stream()
                    .filter(candidate -> candidate.wardId().equals(wardId))
                    .findFirst()
                    .orElse(null);
            if (ward == null) {
                ctx.status(404).json(new ErrorResponse("Ward not found: " + wardId));
                return;
            }
            ctx.json(ward);
        });
    }

    private record ErrorResponse(String error) { }
}
