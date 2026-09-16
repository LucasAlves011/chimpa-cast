package com.screenshare.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomWebSocketHandler.class);
    private static final int MAX_MESSAGE_SIZE = 64 * 1024; // 64 KB
    private static final long CHAT_COOLDOWN_MS = 400L; // 400ms entre mensagens

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final com.screenshare.service.AuthService authService;

    // Sessão WebSocket -> Metadados do Usuário
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    // Nome da Sala -> Conjunto de Sessões WebSocket ativas
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    // Nome da Sala -> Telas ativas sendo compartilhadas (userId -> ScreenInfo)
    private final Map<String, Map<String, ScreenInfo>> roomScreens = new ConcurrentHashMap<>();

    // Sessão WebSocket -> Timestamp da última mensagem enviada no chat (anti-flood)
    private final Map<String, Long> lastChatTimestamps = new ConcurrentHashMap<>();

    public RoomWebSocketHandler(com.screenshare.service.AuthService authService) {
        this.authService = authService;
    }

    public record UserSession(String userId, String username, String roomId, WebSocketSession session) {}
    public record ScreenInfo(String userId, String username, String sessionId, String quality, List<Map<String, Object>> tracks) {}
    public record ActiveRoomStats(int totalActiveScreens, int totalActiveViewers, double currentRateBytesPerSecond) {}

    public ActiveRoomStats calculateCurrentEgressRate() {
        int totalScreens = 0;
        int totalViewers = 0;
        double totalBytesPerSec = 0.0;

        for (Map.Entry<String, Map<String, ScreenInfo>> entry : roomScreens.entrySet()) {
            String roomId = entry.getKey();
            int screensCount = entry.getValue().size();
            if (screensCount > 0) {
                Set<WebSocketSession> members = rooms.get(roomId);
                int membersCount = (members != null) ? members.size() : 0;
                int viewersPerScreen = Math.max(0, membersCount - 1);
                totalScreens += screensCount;
                totalViewers += viewersPerScreen;
                // Estimativa: ~6 Mbps (750.000 bytes/seg) por espectador
                totalBytesPerSec += screensCount * viewersPerScreen * 750_000.0;
            }
        }
        return new ActiveRoomStats(totalScreens, totalViewers, totalBytesPerSec);
    }

    public void broadcastQuotaUpdate(Map<String, Object> quotaData) {
        try {
            Map<String, Object> msg = new HashMap<>(quotaData);
            msg.put("type", "QUOTA_UPDATE");
            String payload = objectMapper.writeValueAsString(msg);
            TextMessage textMessage = new TextMessage(payload);
            for (Set<WebSocketSession> roomSet : rooms.values()) {
                for (WebSocketSession s : roomSet) {
                    if (s.isOpen() && sessions.containsKey(s.getId())) {
                        synchronized (s) {
                            s.sendMessage(textMessage);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao transmitir cota via WebSocket: {}", e.getMessage());
        }
    }

    public void forceDisconnectAllForQuota(String reason) {
        try {
            Map<String, Object> msg = Map.of(
                    "type", "EMERGENCY_QUOTA_CUTOFF",
                    "reason", reason
            );
            String payload = objectMapper.writeValueAsString(msg);
            TextMessage textMessage = new TextMessage(payload);

            log.warn("EXECUTANDO KILL-SWITCH: Desconectando todas as salas ativas! Motivo: {}", reason);

            for (Set<WebSocketSession> roomSet : rooms.values()) {
                for (WebSocketSession s : roomSet) {
                    if (s.isOpen()) {
                        synchronized (s) {
                            try {
                                s.sendMessage(textMessage);
                                s.close(CloseStatus.NORMAL);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            rooms.clear();
            roomScreens.clear();
            sessions.clear();
            lastChatTimestamps.clear();
        } catch (Exception e) {
            log.error("Erro ao executar forceDisconnectAllForQuota: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Nova conexão WebSocket estabelecida com sucesso: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE) {
            log.warn("Mensagem WebSocket rejeitada por tamanho excessivo: {} bytes (sessão: {})", 
                    message.getPayloadLength(), session.getId());
            try {
                session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            } catch (Exception ignored) {}
            return;
        }

        try {
            JsonNode json = objectMapper.readTree(message.getPayload());
            String type = json.path("type").asText();

            switch (type) {
                case "JOIN_ROOM" -> handleJoinRoom(session, json);
                case "SCREEN_STARTED" -> handleScreenStarted(session, json);
                case "SCREEN_STOPPED" -> handleScreenStopped(session);
                case "CHAT_MESSAGE" -> handleChatMessage(session, json);
                default -> log.warn("Tipo de mensagem desconhecido: {}", type);
            }
        } catch (Exception e) {
            log.error("Erro ao processar mensagem WebSocket: {}", e.getMessage(), e);
        }
    }

    private void handleJoinRoom(WebSocketSession session, JsonNode json) throws IOException {
        String token = json.path("token").asText("").trim();
        String password = json.path("password").asText("").trim();
        String credential = !token.isEmpty() ? token : password;

        if (!authService.isValidTokenOrPassword(credential)) {
            log.warn("Conexão rejeitada no JOIN_ROOM: credencial ausente ou inválida para a sessão {}", session.getId());
            sendJson(session, Map.of(
                    "type", "AUTH_ERROR",
                    "message", "Acesso rejeitado: credencial de acesso ausente ou inválida!"
            ));
            session.close(new CloseStatus(4001, "Credencial Incorreta"));
            return;
        }

        String roomId = json.path("room").asText("default").trim().toLowerCase();
        if (roomId.length() > 30) roomId = roomId.substring(0, 30);

        String rawUsername = json.path("username").asText("Anônimo").trim();
        String username = sanitizeUsername(rawUsername);
        String userId = json.path("userId").asText(UUID.randomUUID().toString());

        UserSession userSession = new UserSession(userId, username, roomId, session);
        sessions.put(session.getId(), userSession);

        rooms.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(session);
        roomScreens.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());

        log.info("Usuário [{}] ({}) entrou na sala [{}] com autenticação validada", username, userId, roomId);

        // 1. Enviar estado atual da sala para quem acabou de entrar
        Map<String, Object> roomState = new HashMap<>();
        roomState.put("type", "ROOM_STATE");
        roomState.put("roomId", roomId);
        roomState.put("userId", userId);

        List<Map<String, String>> participants = new ArrayList<>();
        for (WebSocketSession ws : rooms.get(roomId)) {
            UserSession us = sessions.get(ws.getId());
            if (us != null) {
                participants.add(Map.of("userId", us.userId(), "username", us.username()));
            }
        }
        roomState.put("participants", participants);
        roomState.put("screens", roomScreens.get(roomId).values());

        sendJson(session, roomState);

        // 2. Notificar os demais usuários da sala que alguém entrou
        Map<String, Object> notification = Map.of(
                "type", "USER_JOINED",
                "userId", userId,
                "username", username
        );
        broadcastToRoom(roomId, notification, session);
    }

    private void handleScreenStarted(WebSocketSession session, JsonNode json) throws IOException {
        UserSession user = sessions.get(session.getId());
        if (user == null) return;

        String callsSessionId = json.path("sessionId").asText();
        JsonNode tracksNode = json.path("tracks");

        List<Map<String, Object>> tracksList = new ArrayList<>();
        if (tracksNode.isArray()) {
            for (JsonNode t : tracksNode) {
                Map<String, Object> map = new HashMap<>();
                t.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
                tracksList.add(map);
            }
        }

        String quality = json.path("quality").asText("1080p @ 60 FPS").trim();
        ScreenInfo screen = new ScreenInfo(user.userId(), user.username(), callsSessionId, quality, tracksList);
        roomScreens.computeIfAbsent(user.roomId(), k -> new ConcurrentHashMap<>()).put(user.userId(), screen);

        log.info("Usuário [{}] iniciou compartilhamento de tela na sala [{}] (qualidade: {}) com sessionId: {}", 
                user.username(), user.roomId(), quality, callsSessionId);

        Map<String, Object> broadcastMsg = new HashMap<>();
        broadcastMsg.put("type", "SCREEN_STARTED");
        broadcastMsg.put("userId", user.userId());
        broadcastMsg.put("username", user.username());
        broadcastMsg.put("sessionId", callsSessionId);
        broadcastMsg.put("quality", quality);
        broadcastMsg.put("tracks", tracksList);

        broadcastToRoom(user.roomId(), broadcastMsg, null);
    }

    private void handleScreenStopped(WebSocketSession session) throws IOException {
        UserSession user = sessions.get(session.getId());
        if (user == null) return;

        Map<String, ScreenInfo> screens = roomScreens.get(user.roomId());
        if (screens != null && screens.remove(user.userId()) != null) {
            log.info("Usuário [{}] parou de compartilhar tela na sala [{}]", user.username(), user.roomId());

            Map<String, Object> notification = Map.of(
                    "type", "SCREEN_STOPPED",
                    "userId", user.userId()
            );
            broadcastToRoom(user.roomId(), notification, null);
        }
    }

    private void handleChatMessage(WebSocketSession session, JsonNode json) throws IOException {
        UserSession user = sessions.get(session.getId());
        if (user == null) return;

        // Rate Limiting individual de chat (anti-flood)
        long now = System.currentTimeMillis();
        Long lastTimestamp = lastChatTimestamps.get(session.getId());
        if (lastTimestamp != null && (now - lastTimestamp) < CHAT_COOLDOWN_MS) {
            log.debug("Mensagem de chat descartada por flood (sessão: {})", session.getId());
            return;
        }
        lastChatTimestamps.put(session.getId(), now);

        String text = json.path("text").asText("");
        if (text.isBlank()) return;

        // Limite de caracteres do chat
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }

        Map<String, Object> chatMsg = Map.of(
                "type", "CHAT_MESSAGE",
                "userId", user.userId(),
                "username", user.username(),
                "text", text,
                "timestamp", System.currentTimeMillis()
        );
        broadcastToRoom(user.roomId(), chatMsg, null);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        lastChatTimestamps.remove(session.getId());
        UserSession user = sessions.remove(session.getId());
        if (user != null) {
            log.info("Usuário [{}] desconectou da sala [{}]", user.username(), user.roomId());

            // Remove a tela se estava compartilhando
            Map<String, ScreenInfo> screens = roomScreens.get(user.roomId());
            if (screens != null && screens.remove(user.userId()) != null) {
                broadcastToRoom(user.roomId(), Map.of(
                        "type", "SCREEN_STOPPED",
                        "userId", user.userId()
                ), null);
            }

            Set<WebSocketSession> roomSet = rooms.get(user.roomId());
            if (roomSet != null) {
                roomSet.remove(session);
                if (roomSet.isEmpty()) {
                    rooms.remove(user.roomId());
                    roomScreens.remove(user.roomId());
                } else {
                    broadcastToRoom(user.roomId(), Map.of(
                            "type", "USER_LEFT",
                            "userId", user.userId(),
                            "username", user.username()
                    ), null);
                }
            }
        }
    }

    private String sanitizeUsername(String input) {
        if (input == null || input.isBlank()) return "Anônimo";
        String cleaned = input.replaceAll("[<>&\"'/\\\\]", "").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 30) {
            cleaned = cleaned.substring(0, 30).trim();
        }
        return cleaned.isEmpty() ? "Anônimo" : cleaned;
    }

    private void broadcastToRoom(String roomId, Map<String, Object> message, WebSocketSession exclude) throws IOException {
        Set<WebSocketSession> roomSessions = rooms.get(roomId);
        if (roomSessions == null) return;

        String payload = objectMapper.writeValueAsString(message);
        TextMessage textMessage = new TextMessage(payload);

        for (WebSocketSession s : roomSessions) {
            if (s.isOpen() && (exclude == null || !s.getId().equals(exclude.getId()))) {
                synchronized (s) {
                    s.sendMessage(textMessage);
                }
            }
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> message) throws IOException {
        if (session.isOpen()) {
            String payload = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }
}
