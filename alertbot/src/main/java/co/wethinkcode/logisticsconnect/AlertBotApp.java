package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Listens to package-status-topic and "posts" an alert when a hub's delay stage reaches the threshold. */
public class AlertBotApp {

    static final int PORT = 7054;
    static final int THRESHOLD = 5;
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final List<Map<String, Object>> alerts = new CopyOnWriteArrayList<>();

    public static void main(String[] args) {
        Thread subscriber = new Thread(AlertBotApp::subscribeLoop);
        subscriber.setDaemon(true);
        subscriber.start();

        Javalin app = Javalin.create();
        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/alerts", ctx -> ctx.json(alerts));
        app.start(PORT);
    }

    static void subscribeLoop() {
        while (true) {
            try {
                Connection connection = new ActiveMQConnectionFactory(MqConfig.BROKER_URL).createConnection();
                connection.setClientID("alertbot");
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Topic topic = session.createTopic(MqConfig.TOPIC);
                MessageConsumer consumer = session.createDurableSubscriber(topic, "alertbot-sub");
                consumer.setMessageListener(new AlertListener());
                connection.start();
                System.out.println("AlertBot subscribed to " + MqConfig.TOPIC);
                return;
            } catch (Exception e) {
                System.out.println("Broker not ready, retrying in 3 seconds");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    static class AlertListener implements MessageListener {
        @Override
        public void onMessage(Message message) {
            try {
                String text = ((TextMessage) message).getText();
                JsonNode node = MAPPER.readTree(text);
                String hubId = node.get("hubId").asText();
                int stage = node.get("stage").asInt();

                if (stage >= THRESHOLD) {
                    String post = "Delay alert: hub " + hubId + " is at delay stage " + stage
                            + ". Expect late parcels.";
                    Map<String, Object> alert = new LinkedHashMap<>();
                    alert.put("hubId", hubId);
                    alert.put("stage", stage);
                    alert.put("post", post);
                    alert.put("postedAt", Instant.now().toString());
                    alerts.add(alert);
                    System.out.println("[SOCIAL MEDIA POST] " + post);
                }
            } catch (Exception e) {
                System.out.println("Ignored a bad message: " + e.getMessage());
            }
        }
    }
}