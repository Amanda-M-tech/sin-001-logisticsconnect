package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the transit delay stage (0-8) per hub and announces every change on package-status-topic. */
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
        int stage = stages.getOrDefault(hubId, 0);
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
        boolean published = publish(hubId, stage);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hubId", hubId);
        result.put("stage", stage);
        result.put("published", published);
        ctx.json(result);
    }

    /** Pins a notice on the topic. Returns false if the broker could not be reached. */
    static boolean publish(String hubId, int stage) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("hubId", hubId);
            message.put("stage", stage);
            message.put("timestamp", Instant.now().toString());
            String json = MAPPER.writeValueAsString(message);

            Connection connection = new ActiveMQConnectionFactory(MqConfig.BROKER_URL).createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(MqConfig.TOPIC);
            MessageProducer producer = session.createProducer(topic);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            TextMessage textMessage = session.createTextMessage(json);
            producer.send(textMessage);
            connection.close();

            System.out.println("Published: " + json);
            return true;
        } catch (Exception e) {
            System.out.println("Could not publish (is the broker running?): " + e.getMessage());
            return false;
        }
    }
}