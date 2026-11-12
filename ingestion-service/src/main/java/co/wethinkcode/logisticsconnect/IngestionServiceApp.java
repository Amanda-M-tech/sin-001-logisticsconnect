
package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Stage 1, step 1: read hubs-global.csv and clean every field. */
public class IngestionServiceApp {

    static final int PORT = 7050;

    // The official spellings. Anything that "looks like" one of these gets turned into it.
    static final String[] PROVINCES = {
            "Eastern Cape", "Free State", "Gauteng", "KwaZulu-Natal", "Limpopo",
            "Mpumalanga", "North West", "Northern Cape", "Western Cape"
    };

    // Values that really mean "nobody filled this in".
    static final String[] PLACEHOLDERS = {"", "n/a", "na", "tbd", "unknown", "-", "nan"};

    public static void main(String[] args) throws IOException {
        List<Hub> hubs = loadHubs("/hubs-global.csv");

        for (Hub hub : hubs) {
            System.out.println(hub);
        }
        System.out.println("Cleaned rows: " + hubs.size());

        Javalin app = Javalin.create();
        app.get("/health", ctx -> ctx.result("OK"));
        app.start(PORT);
    }

    // ---------------------------------------------------------------- reading

    static List<Hub> loadHubs(String resourceName) throws IOException {
        List<Hub> hubs = new ArrayList<>();

        InputStream in = IngestionServiceApp.class.getResourceAsStream(resourceName);
        if (in == null) {
            throw new IllegalStateException(resourceName + " not found in src/main/resources");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = reader.readLine();              // first line is the header, skip it

        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", -1);     // -1 keeps empty fields at the end
            if (parts.length < 4) {
                continue;                             // broken row, skip it
            }

            Hub hub = new Hub();
            hub.hubId = cleanId(parts[0]);
            hub.province = cleanProvince(parts[1]);
            hub.sortingCenter = cleanName(parts[2]);
            hub.active = cleanFlag(parts[3]);

            if (hub.hubId != null) {                  // a row with no id is useless
                hubs.add(hub);
            }
        }
        reader.close();
        return hubs;
    }

    // --------------------------------------------------------------- cleaning

    /** Trim the ends, squash double spaces, and turn placeholders into null. */
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
        return text.toUpperCase();                    // h-501 -> H-501
    }

    /** Match against the official list by comparing letters only, so "Kwa-Zulu Natal" still matches. */
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
        return titleCase(text);                       // not in the list, at least tidy it up
    }

    static String cleanName(String raw) {
        String text = cleanText(raw);
        if (text == null) {
            return null;
        }
        return titleCase(text);
    }

    /** Y / yes / true / 1  ->  true.   N / no / false / 0  ->  false.   Anything else -> null. */
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

    // ------------------------------------------------------------------ model

    /** One cleaned row. Fields are null when the CSV had nothing usable. */
    static class Hub {
        String hubId;
        String province;
        String sortingCenter;
        Boolean active;

        @Override
        public String toString() {
            return hubId + " | " + province + " | " + sortingCenter + " | " + active;
        }
    }
}