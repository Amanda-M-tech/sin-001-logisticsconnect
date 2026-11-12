package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage 1: read hubs-global.csv, clean every field, remove duplicates, serve over REST. */
public class IngestionServiceApp {

    static final int PORT = 7050;

    static final String[] PROVINCES = {
            "Eastern Cape", "Free State", "Gauteng", "KwaZulu-Natal", "Limpopo",
            "Mpumalanga", "North West", "Northern Cape", "Western Cape"
    };

    static final String[] PLACEHOLDERS = {"", "n/a", "na", "tbd", "unknown", "-", "nan"};

    // The final, cleaned, de-duplicated list that the endpoints serve.
    static List<Hub> cleanedHubs = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        List<Hub> rawHubs = loadHubs("/hubs-global.csv");
        cleanedHubs = deduplicate(rawHubs);

        System.out.println("Rows read: " + rawHubs.size());
        System.out.println("After removing duplicates: " + cleanedHubs.size());
        for (Hub hub : cleanedHubs) {
            System.out.println(hub);
        }

        Javalin app = Javalin.create();
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/hubs", IngestionServiceApp::getAllHubs);
        app.get("/hubs/{hubId}", IngestionServiceApp::getOneHub);
        app.start(PORT);
    }

    // -------------------------------------------------------------- endpoints

    static void getAllHubs(Context ctx) {
        ctx.json(cleanedHubs);
    }

    static void getOneHub(Context ctx) {
        String id = ctx.pathParam("hubId").trim().toUpperCase();

        for (Hub hub : cleanedHubs) {
            boolean isMainId = id.equals(hub.hubId);
            boolean isOldId = hub.aliases.contains(id);   // an id that was merged into this hub
            if (isMainId || isOldId) {
                ctx.json(hub);
                return;
            }
        }
        ctx.status(404).result("Unknown hub " + id);
    }

    // ---------------------------------------------------------------- reading

    static List<Hub> loadHubs(String resourceName) throws IOException {
        List<Hub> hubs = new ArrayList<>();

        InputStream in = IngestionServiceApp.class.getResourceAsStream(resourceName);
        if (in == null) {
            throw new IllegalStateException(resourceName + " not found in src/main/resources");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = reader.readLine();              // skip the header

        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 4) {
                continue;
            }

            Hub hub = new Hub();
            hub.hubId = cleanId(parts[0]);
            hub.province = cleanProvince(parts[1]);
            hub.sortingCenter = cleanName(parts[2]);
            hub.active = cleanFlag(parts[3]);

            if (hub.hubId != null) {
                hubs.add(hub);
            }
        }
        reader.close();
        return hubs;
    }

    // --------------------------------------------------------------- cleaning

    static String cleanText(String raw) {
        String text = raw.trim().replaceAll("\\s+", " ");
        for (String placeholder : PLACEHOLDERS) {
            if (text.equalsIgnoreCase(placeholder)) {
                return null;
            }
        }
        return text;
    }

    static String cleanId(String raw) {
        String text = cleanText(raw);
        if (text == null) {
            return null;
        }
        return text.toUpperCase();
    }

    static String cleanProvince(String raw) {
        String text = cleanText(raw);
        if (text == null) {
            return null;
        }
        String key = lettersOnly(text);
        for (String province : PROVINCES) {
            if (lettersOnly(province).equals(key)) {
                return province;
            }
        }
        return titleCase(text);
    }

    static String cleanName(String raw) {
        String text = cleanText(raw);
        if (text == null) {
            return null;
        }
        return titleCase(text);
    }

    static Boolean cleanFlag(String raw) {
        String text = cleanText(raw);
        if (text == null) {
            return null;
        }
        text = text.toLowerCase();
        if (text.equals("y") || text.equals("yes") || text.equals("true") || text.equals("1")) {
            return true;
        }
        if (text.equals("n") || text.equals("no") || text.equals("false") || text.equals("0")) {
            return false;
        }
        return null;
    }

    static String lettersOnly(String text) {
        return text.toLowerCase().replaceAll("[^a-z]", "");
    }

    static String titleCase(String text) {
        StringBuilder result = new StringBuilder();
        String[] words = text.split(" ");
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            result.append(word.substring(1).toLowerCase());
        }
        return result.toString();
    }

    // ---------------------------------------------------------- deduplication

    /**
     * Rule: two rows are the same hub if they have the same sorting center
     * (compared by letters only). Rows with no sorting center are never merged.
     */
    static List<Hub> deduplicate(List<Hub> hubs) {
        Map<String, List<Hub>> groups = new LinkedHashMap<>();

        for (Hub hub : hubs) {
            String key;
            if (hub.sortingCenter != null) {
                key = lettersOnly(hub.sortingCenter);
            } else {
                key = "no-center-" + hub.hubId;
            }

            List<Hub> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(hub);
        }

        List<Hub> result = new ArrayList<>();
        for (List<Hub> group : groups.values()) {
            result.add(mergeGroup(group));
        }
        return result;
    }

    /**
     * Winner = the row with the most filled-in fields. If tied, the lowest hub id.
     * The losing ids are kept as aliases, and any empty field on the winner
     * is filled in from the losers.
     */
    static Hub mergeGroup(List<Hub> group) {
        Hub best = group.get(0);
        for (Hub candidate : group) {
            int candidateScore = candidate.filledFields();
            int bestScore = best.filledFields();
            if (candidateScore > bestScore
                    || (candidateScore == bestScore && candidate.hubId.compareTo(best.hubId) < 0)) {
                best = candidate;
            }
        }

        Hub merged = new Hub();
        merged.hubId = best.hubId;
        merged.province = best.province;
        merged.sortingCenter = best.sortingCenter;
        merged.active = best.active;

        for (Hub other : group) {
            if (other == best) {
                continue;
            }
            merged.aliases.add(other.hubId);
            if (merged.province == null) {
                merged.province = other.province;
            }
            if (merged.sortingCenter == null) {
                merged.sortingCenter = other.sortingCenter;
            }
            if (merged.active == null) {
                merged.active = other.active;
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------ model

    /** Public fields so Jackson can turn this into JSON. */
    public static class Hub {
        public String hubId;
        public String province;
        public String sortingCenter;
        public Boolean active;
        public List<String> aliases = new ArrayList<>();

        int filledFields() {
            int count = 0;
            if (province != null) count++;
            if (sortingCenter != null) count++;
            if (active != null) count++;
            return count;
        }

        @Override
        public String toString() {
            return hubId + " | " + province + " | " + sortingCenter + " | " + active + " | aliases " + aliases;
        }
    }
}