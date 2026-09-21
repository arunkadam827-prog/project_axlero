package logstream_backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LogWebSocketHandler
        extends TextWebSocketHandler {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    private final Set<WebSocketSession> sessions =
            ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(
            WebSocketSession session) {

        sessions.add(session);

        System.out.println(
                "WebSocket client connected: "
                        + session.getId()
        );

        sendStatus(session, "connected");
    }

    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status) {

        sessions.remove(session);

        System.out.println(
                "WebSocket client disconnected: "
                        + session.getId()
        );
    }

    /**
     * Broadcast one log to every connected frontend.
     */
    public void broadcast(Object log) {

        String json;

        try {

            json = objectMapper.writeValueAsString(log);

        } catch (Exception e) {

            e.printStackTrace();
            return;
        }

        TextMessage message =
                new TextMessage(json);

        for (WebSocketSession session : sessions) {

            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }

            try {

                synchronized (session) {
                    session.sendMessage(message);
                }

            } catch (IOException e) {

                sessions.remove(session);

                try {
                    session.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void sendStatus(
            WebSocketSession session,
            String status) {

        try {

            String json =
                    objectMapper.writeValueAsString(
                            java.util.Map.of(
                                    "type",
                                    "connection",
                                    "status",
                                    status
                            )
                    );

            session.sendMessage(
                    new TextMessage(json)
            );

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}