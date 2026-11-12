package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/** Calculates an estimated arrival window from the hub details and the current delay stage. */
public class TransitServiceApp {

    static final int PORT = 7053;
    static final String HUB_URL = "http://localhost:7051";
    static final String STAGE_URL = "http://localhost:7052";
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static final int BASE_HOURS = 24;
    static final int HOURS_PER_STAGE = 6;
    static final int SUSPENDED_STAGE = 8;

    public static void main(String[] args) {
        Javalin app = Javalin.create();
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/eta/{hubId}", TransitServiceApp::getEta);
        app.start(PORT);
    }

    static void getEta(Context ctx) throws Exception {
        String requested = ctx.pathParam("hubId").trim().toUpperCase();

        // 1. REST call to hub-service
        HttpResponse<String> hubResponse = get(HUB_URL + "/hubs/" + requested);
        if (hubResponse == null) {
            ctx.status(503).result("hub-service is unreachable");
            return;
        }
        if (hubResponse.statusCode() == 404) {
            ctx.status(404).result("Unknown hub " + requested);
            return;
        }
        Map<String, Object> hub = MAPPER.readValue(hubResponse.body(), new TypeReference<Map<String, Object>>() { });
        String hubId = hub.get("hubId").toString();       // the main id, even if an old alias was asked for

        // 2. REST call to delay-stage-service (Part 3 replaces this with MQ)
        HttpResponse<String> stageResponse = get(STAGE_URL + "/delay-stage/" + hubId);
        if (stageResponse == null || stageResponse.statusCode() != 200) {
            ctx.status(503).result("delay-stage-service is unreachable");
            return;
        }
        int stage = MAPPER.readTree(stageResponse.body()).get("stage").asInt();

        ctx.json(buildEta(hub, stage));
    }

    static Map<String, Object> buildEta(Map<String, Object> hub, int stage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hubId", hub.get("hubId"));
        result.put("province", hub.get("province"));
        result.put("sortingCenter", hub.get("sortingCenter"));
        result.put("hubActive", hub.get("active"));
        result.put("delayStage", stage);

        if (stage >= SUSPENDED_STAGE) {
            result.put("status", "SUSPENDED");
            result.put("estimatedArrivalFrom", null);
            result.put("estimatedArrivalTo", null);
        } else {
            Instant from = Instant.now().plus(BASE_HOURS + (long) stage * HOURS_PER_STAGE, ChronoUnit.HOURS);
            Instant to = from.plus(4 + (long) stage * 2, ChronoUnit.HOURS);   // window widens with delay
            result.put("status", stage == 0 ? "ON_TIME" : "DELAYED");
            result.put("estimatedArrivalFrom", from.toString());
            result.put("estimatedArrivalTo", to.toString());
        }
        return result;
    }

    /** Returns null if the other service can't be reached. */
    static HttpResponse<String> get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return null;
        }
    }
}