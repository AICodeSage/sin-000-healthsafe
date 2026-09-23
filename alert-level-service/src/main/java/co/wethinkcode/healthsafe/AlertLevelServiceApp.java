package co.wethinkcode.healthsafe;

import io.javalin.Javalin;
import java.util.concurrent.atomic.AtomicInteger;

public class AlertLevelServiceApp {
    private static final int MINIMUM_LEVEL = 0;
    private static final int MAXIMUM_LEVEL = 8;

    public static void main(String[] args) {
        AtomicInteger currentLevel = new AtomicInteger(MINIMUM_LEVEL);
        Javalin app = Javalin.create().start(7032);

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/alert-level", ctx -> ctx.json(new AlertLevel(currentLevel.get())));
        app.put("/alert-level", ctx -> {
            AlertLevel requested;
            try {
                requested = ctx.bodyAsClass(AlertLevel.class);
            } catch (RuntimeException exception) {
                ctx.status(400).json(new ErrorResponse("Request body must be JSON: {\"level\": 0-8}"));
                return;
            }
            if (requested == null || requested.level() < MINIMUM_LEVEL || requested.level() > MAXIMUM_LEVEL) {
                ctx.status(400).json(new ErrorResponse("level must be an integer between 0 and 8"));
                return;
            }
            currentLevel.set(requested.level());
            ctx.json(new AlertLevel(currentLevel.get()));
        });
    }

    public record AlertLevel(int level) { }

    private record ErrorResponse(String error) { }
}
