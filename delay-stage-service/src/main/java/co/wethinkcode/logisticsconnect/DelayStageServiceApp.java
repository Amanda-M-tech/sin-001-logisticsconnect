package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the transit delay stage (0-8) per hub, in memory. */
public class DelayStageServiceApp {

    static final int PORT = 7052;
    static final int MAX_STAGE = 8;
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final Map<String, Integer> stages = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Javalin app = Javalin.create();
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/delay-stage/{hubId}", DelayStageServiceApp::getStage);
        app.post("/delay-stage/{hubId}", DelayStageServiceApp::setStage);
        app.start(PORT);
    }

    static void getStage(Context ctx) {
        String hubId = ctx.pathParam("hubId").trim().toUpperCase();
        int stage = stages.getOrDefault(hubId, 0);          // no entry = no delay
        ctx.json(Map.of("hubId", hubId, "stage", stage));
    }

    static void setStage(Context ctx) {
        String hubId = ctx.pathParam("hubId").trim().toUpperCase();

        JsonNode body;
        try {
            body = MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            ctx.status(400).result("Body must be JSON like {\"stage\": 3}");
            return;
        }
        if (body == null || !body.has("stage") || !body.get("stage").isInt()) {
            ctx.status(400).result("Body must be JSON like {\"stage\": 3}");
            return;
        }

        int stage = body.get("stage").asInt();
        if (stage < 0 || stage > MAX_STAGE) {
            ctx.status(400).result("stage must be between 0 and " + MAX_STAGE);
            return;
        }

        stages.put(hubId, stage);
        ctx.json(Map.of("hubId", hubId, "stage", stage));
    }
}