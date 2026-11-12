package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Place-name source of truth. Loads the cleaned hubs from ingestion-service over REST. */
public class HubServiceApp {

    static final int PORT = 7051;
    static final String INGESTION_URL = "http://localhost:7050";
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static List<Map<String, Object>> hubs = new ArrayList<>();

    public static void main(String[] args) {
        loadHubs();

        Javalin app = Javalin.create();
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/hubs", HubServiceApp::getAllHubs);
        app.get("/hubs/{hubId}", HubServiceApp::getOneHub);
        app.get("/provinces", HubServiceApp::getProvinces);
        app.get("/sorting-centers", HubServiceApp::getSortingCenters);
        app.post("/reload", HubServiceApp::reload);
        app.start(PORT);
    }

    // -------------------------------------------------------------- endpoints

    static void getAllHubs(Context ctx) {
        ctx.json(hubs);
    }

    static void getOneHub(Context ctx) {
        String id = ctx.pathParam("hubId").trim().toUpperCase();

        for (Map<String, Object> hub : hubs) {
            if (id.equals(hub.get("hubId"))) {
                ctx.json(hub);
                return;
            }
            Object aliasesObject = hub.get("aliases");
            if (aliasesObject instanceof List) {
                List<?> aliases = (List<?>) aliasesObject;
                if (aliases.contains(id)) {
                    ctx.json(hub);
                    return;
                }
            }
        }
        ctx.status(404).result("Unknown hub " + id);
    }

    static void getProvinces(Context ctx) {
        TreeSet<String> provinces = new TreeSet<>();
        for (Map<String, Object> hub : hubs) {
            Object province = hub.get("province");
            if (province != null) {
                provinces.add(province.toString());
            }
        }
        ctx.json(provinces);
    }

    static void getSortingCenters(Context ctx) {
        String wantedProvince = ctx.queryParam("province");   // optional: ?province=Gauteng
        TreeSet<String> centers = new TreeSet<>();

        for (Map<String, Object> hub : hubs) {
            Object center = hub.get("sortingCenter");
            Object province = hub.get("province");
            if (center == null) {
                continue;
            }
            if (wantedProvince != null
                    && (province == null || !wantedProvince.equalsIgnoreCase(province.toString()))) {
                continue;
            }
            centers.add(center.toString());
        }
        ctx.json(centers);
    }

    static void reload(Context ctx) {
        loadHubs();
        ctx.json(Map.of("loaded", hubs.size()));
    }

    // ---------------------------------------------- REST call to ingestion-service

    /** Retries for ~30 seconds so it doesn't matter which service you start first. */
    static void loadHubs() {
        for (int attempt = 1; attempt <= 15; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(INGESTION_URL + "/hubs")).GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    hubs = MAPPER.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() { });
                    System.out.println("Loaded " + hubs.size() + " hubs from ingestion-service");
                    return;
                }
            } catch (Exception e) {
                System.out.println("ingestion-service not ready (attempt " + attempt + ")");
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
        System.out.println("WARNING: no hub data loaded. Start ingestion-service, then POST /reload");
    }
}