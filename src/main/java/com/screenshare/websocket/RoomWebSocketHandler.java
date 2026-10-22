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
    private final com.screenshare.service.RoomSettingsService roomSettingsService;

    // Sessão WebSocket -> Metadados do Usuário
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    // Nome da Sala -> Conjunto de Sessões WebSocket ativas
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    // Nome da Sala -> Telas ativas sendo compartilhadas (userId -> ScreenInfo)
    private final Map<String, Map<String, ScreenInfo>> roomScreens = new ConcurrentHashMap<>();

    // Sessão WebSocket -> Timestamp da última mensagem enviada no chat (anti-flood)
    private final Map<String, Long> lastChatTimestamps = new ConcurrentHashMap<>();

    public RoomWebSocketHandler(com.screenshare.service.AuthService authService,
                                com.screenshare.service.RoomSettingsService roomSettingsService) {
        this.authService = authService;
        this.roomSettingsService = roomSettingsService;
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

    public boolean hasActiveScreens() {
        return roomScreens.values().stream().anyMatch(screens -> !screens.isEmpty());
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
                        try {
                            synchronized (s) {
                                s.sendMessage(textMessage);
                            }
                        } catch (Exception e) {
                            log.debug("Sessão inativa detectada durante broadcast de cota ({}): {}", s.getId(), e.getMessage());
                            cleanupDeadSession(s);
                        }
                    } else if (!s.isOpen()) {
                        cleanupDeadSession(s);
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
                case "KICK_SCREEN" -> handleKickScreen(session, json);
                case "CHAT_MESSAGE" -> handleChatMessage(session, json);
                case "PING" -> sendJson(session, Map.of("type", "PONG"));
                default -> log.warn("Tipo de mensagem desconhecido: {}", type);
            }
        } catch (Exception e) {
            log.error("Erro ao processar mensagem WebSocket: {}", e.getMessage(), e);
        }
    }

    public void broadcastSettingsUpdate() {
        try {
            Map<String, Object> msg = Map.of(
                    "type", "SETTINGS_UPDATED",
                    "allowRemoteScreenKick", roomSettingsService.isAllowRemoteScreenKick()
            );
            String payload = objectMapper.writeValueAsString(msg);
            TextMessage textMessage = new TextMessage(payload);
            for (Set<WebSocketSession> roomSet : rooms.values()) {
                for (WebSocketSession s : roomSet) {
                    if (s.isOpen()) {
                        synchronized (s) {
                            try {
                                s.sendMessage(textMessage);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao transmitir atualização de configurações: {}", e.getMessage());
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

        Set<WebSocketSession> roomSet = rooms.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>());
        Map<String, ScreenInfo> screensMap = roomScreens.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());

        // Limpa sessões antigas/zumbis do mesmo userId ou mesmo username na sala
        List<WebSocketSession> staleSessions = new ArrayList<>();
        for (WebSocketSession ws : roomSet) {
            if (ws.getId().equals(session.getId())) continue;
            UserSession us = sessions.get(ws.getId());
            if (!ws.isOpen() || us == null || us.userId().equals(userId) || us.username().equalsIgnoreCase(username)) {
                staleSessions.add(ws);
            }
        }

        for (WebSocketSession staleWs : staleSessions) {
            UserSession staleUser = sessions.remove(staleWs.getId());
            lastChatTimestamps.remove(staleWs.getId());
            roomSet.remove(staleWs);
            if (staleUser != null) {
                ScreenInfo removedScreen = screensMap.remove(staleUser.userId());
                if (removedScreen != null) {
                    log.info("Removendo tela órfã de [{}] ({}) ao reconectar na sala [{}]",
                            staleUser.username(), staleUser.userId(), roomId);
                    broadcastToRoom(roomId, Map.of(
                            "type", "SCREEN_STOPPED",
                            "userId", staleUser.userId(),
                            "username", staleUser.username()
                    ), session);
                }
            }
            try {
                if (staleWs.isOpen()) {
                    staleWs.close(new CloseStatus(4002, "Sessão substituída por nova conexão"));
                }
            } catch (Exception ignored) {}
        }

        // Também remove qualquer tela órfã residual em roomScreens com mesmo userId ou username
        List<String> orphanScreenUserIds = new ArrayList<>();
        for (Map.Entry<String, ScreenInfo> entry : screensMap.entrySet()) {
            ScreenInfo si = entry.getValue();
            if (entry.getKey().equals(userId) || (si != null && si.username().equalsIgnoreCase(username))) {
                orphanScreenUserIds.add(entry.getKey());
            }
        }
        for (String orphanId : orphanScreenUserIds) {
            ScreenInfo removed = screensMap.remove(orphanId);
            if (removed != null) {
                log.info("Limpando tela residual de [{}] ({}) na entrada da sala [{}]", removed.username(), orphanId, roomId);
                broadcastToRoom(roomId, Map.of(
                        "type", "SCREEN_STOPPED",
                        "userId", orphanId,
                        "username", removed.username()
                ), session);
            }
        }

        UserSession userSession = new UserSession(userId, username, roomId, session);
        sessions.put(session.getId(), userSession);
        roomSet.add(session);

        log.info("Usuário [{}] ({}) entrou na sala [{}] com autenticação validada", username, userId, roomId);

        // 1. Enviar estado atual da sala para quem acabou de entrar
        Map<String, Object> roomState = new HashMap<>();
        roomState.put("type", "ROOM_STATE");
        roomState.put("roomId", roomId);
        roomState.put("userId", userId);

        List<Map<String, String>> participants = getRoomParticipants(roomId);
        roomState.put("participants", participants);
        roomState.put("screens", screensMap.values());
        roomState.put("allowRemoteScreenKick", roomSettingsService.isAllowRemoteScreenKick());

        sendJson(session, roomState);

        // 2. Notificar os demais usuários da sala que alguém entrou (com a lista atualizada de participantes)
        Map<String, Object> notification = Map.of(
                "type", "USER_JOINED",
                "userId", userId,
                "username", username,
                "participants", participants
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
        Map<String, ScreenInfo> screens = roomScreens.computeIfAbsent(user.roomId(), k -> new ConcurrentHashMap<>());

        // Remove qualquer tela anterior do mesmo username (caso tenha ficado com userId antigo)
        List<String> duplicateUserIds = new ArrayList<>();
        for (Map.Entry<String, ScreenInfo> entry : screens.entrySet()) {
            ScreenInfo existing = entry.getValue();
            if (!entry.getKey().equals(user.userId()) && existing != null && existing.username().equalsIgnoreCase(user.username())) {
                duplicateUserIds.add(entry.getKey());
            }
        }
        for (String dupId : duplicateUserIds) {
            ScreenInfo removed = screens.remove(dupId);
            if (removed != null) {
                broadcastToRoom(user.roomId(), Map.of(
                        "type", "SCREEN_STOPPED",
                        "userId", dupId,
                        "username", removed.username()
                ), null);
            }
        }

        ScreenInfo screen = new ScreenInfo(user.userId(), user.username(), callsSessionId, quality, tracksList);
        screens.put(user.userId(), screen);

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
        Set<String> stoppedUserIds = new LinkedHashSet<>();
        stoppedUserIds.add(user.userId());

        if (screens != null) {
            screens.remove(user.userId());
            // Remove também qualquer tela órfã com o mesmo username na sala
            for (Map.Entry<String, ScreenInfo> entry : new ArrayList<>(screens.entrySet())) {
                ScreenInfo si = entry.getValue();
                if (si != null && si.username().equalsIgnoreCase(user.username())) {
                    screens.remove(entry.getKey());
                    stoppedUserIds.add(entry.getKey());
                }
            }
        }

        log.info("Usuário [{}] parou de compartilhar tela na sala [{}]", user.username(), user.roomId());

        for (String uid : stoppedUserIds) {
            Map<String, Object> notification = Map.of(
                    "type", "SCREEN_STOPPED",
                    "userId", uid,
                    "username", user.username()
            );
            broadcastToRoom(user.roomId(), notification, null);
        }
    }

    private void handleKickScreen(WebSocketSession session, JsonNode json) throws IOException {
        UserSession requester = sessions.get(session.getId());
        if (requester == null) return;

        if (!roomSettingsService.isAllowRemoteScreenKick()) {
            log.warn("Tentativa de kick de tela por [{}] bloqueada: funcionalidade desativada.", requester.username());
            sendJson(session, Map.of(
                    "type", "KICK_SCREEN_REJECTED",
                    "reason", "O encerramento remoto de telas está desativado pelo administrador."
            ));
            return;
        }

        String targetUserId = json.path("targetUserId").asText("").trim();
        if (targetUserId.isEmpty()) return;

        // Procura a sessão do usuário alvo na mesma sala
        Set<WebSocketSession> roomSet = rooms.get(requester.roomId());
        if (roomSet == null) return;

        UserSession targetSession = null;
        for (WebSocketSession s : roomSet) {
            UserSession us = sessions.get(s.getId());
            if (us != null && us.userId().equals(targetUserId)) {
                targetSession = us;
                break;
            }
        }

        Map<String, ScreenInfo> screens = roomScreens.get(requester.roomId());
        ScreenInfo removedScreen = (screens != null) ? screens.remove(targetUserId) : null;
        String targetUsername = (targetSession != null)
                ? targetSession.username()
                : (removedScreen != null ? removedScreen.username() : "usuário");

        log.info("Usuário [{}] encerrou remotamente a tela de [{}] ({}) na sala [{}]",
                requester.username(), targetUsername, targetUserId, requester.roomId());

        if (targetSession != null) {
            // 1. Envia ordem compulsória de parada para o transmissor alvo se estiver conectado
            sendJson(targetSession.session(), Map.of(
                    "type", "FORCE_STOP_SCREEN",
                    "kickedBy", requester.username(),
                    "reason", "Sua transmissão foi encerrada por " + requester.username() + "."
            ));
        }

        // 2. Notifica a sala inteira (mesmo que a sessão do alvo já fosse órfã/fantasma)
        broadcastToRoom(requester.roomId(), Map.of(
                "type", "SCREEN_STOPPED",
                "userId", targetUserId,
                "username", targetUsername
        ), null);

        // 3. Mensagem de sistema no chat se havia sessão ou tela ativa
        if (targetSession != null || removedScreen != null) {
            broadcastToRoom(requester.roomId(), Map.of(
                    "type", "CHAT_MESSAGE",
                    "userId", "system",
                    "username", "Sistema",
                    "text", "🛑 A transmissão de " + targetUsername + " foi encerrada por " + requester.username() + ".",
                    "timestamp", System.currentTimeMillis()
            ), null);
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
        cleanupSession(session);
    }

    private void cleanupDeadSession(WebSocketSession session) {
        try {
            cleanupSession(session);
        } catch (Exception e) {
            log.debug("Erro ao limpar sessão morta {}: {}", session.getId(), e.getMessage());
        }
    }

    private void cleanupSession(WebSocketSession session) throws IOException {
        lastChatTimestamps.remove(session.getId());
        UserSession user = sessions.remove(session.getId());
        if (user != null) {
            log.info("Usuário [{}] ({}) desconectou da sala [{}]", user.username(), user.userId(), user.roomId());

            Set<WebSocketSession> roomSet = rooms.get(user.roomId());
            if (roomSet != null) {
                roomSet.remove(session);
            }

            // Verifica se o mesmo userId já não reconectou em outra sessão ativa na mesma sala
            boolean hasActiveReplacementSession = false;
            if (roomSet != null) {
                for (WebSocketSession ws : roomSet) {
                    UserSession us = sessions.get(ws.getId());
                    if (ws.isOpen() && us != null && us.userId().equals(user.userId())) {
                        hasActiveReplacementSession = true;
                        break;
                    }
                }
            }

            if (!hasActiveReplacementSession) {
                // Remove a tela se estava compartilhando
                Map<String, ScreenInfo> screens = roomScreens.get(user.roomId());
                if (screens != null && screens.remove(user.userId()) != null) {
                    broadcastToRoom(user.roomId(), Map.of(
                            "type", "SCREEN_STOPPED",
                            "userId", user.userId(),
                            "username", user.username()
                    ), null);
                }

                if (roomSet != null) {
                    if (roomSet.isEmpty()) {
                        rooms.remove(user.roomId());
                        roomScreens.remove(user.roomId());
                    } else {
                        broadcastToRoom(user.roomId(), Map.of(
                                "type", "USER_LEFT",
                                "userId", user.userId(),
                                "username", user.username(),
                                "participants", getRoomParticipants(user.roomId())
                        ), null);
                    }
                }
            }
        }
    }

    public Map<String, Integer> getRoomsCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String r : List.of("principal", "sala-2", "sala-3")) {
            counts.put(r, getRoomParticipants(r).size());
        }
        return counts;
    }

    private List<Map<String, String>> getRoomParticipants(String roomId) {
        Set<WebSocketSession> roomSet = rooms.get(roomId);
        if (roomSet == null) return Collections.emptyList();

        List<Map<String, String>> participants = new ArrayList<>();
        Set<String> seenUserIds = new HashSet<>();
        for (WebSocketSession ws : roomSet) {
            if (!ws.isOpen()) continue;
            UserSession us = sessions.get(ws.getId());
            if (us != null && seenUserIds.add(us.userId())) {
                participants.add(Map.of("userId", us.userId(), "username", us.username()));
            }
        }
        return participants;
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
            if (exclude != null && s.getId().equals(exclude.getId())) {
                continue;
            }
            if (s.isOpen()) {
                try {
                    synchronized (s) {
                        s.sendMessage(textMessage);
                    }
                } catch (Exception e) {
                    log.warn("Falha ao enviar mensagem para sessão {} na sala [{}], limpando sessão: {}",
                            s.getId(), roomId, e.getMessage());
                    cleanupDeadSession(s);
                }
            } else {
                cleanupDeadSession(s);
            }
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> message) throws IOException {
        if (session.isOpen()) {
            String payload = objectMapper.writeValueAsString(message);
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (Exception e) {
                log.debug("Falha em sendJson para sessão {}: {}", session.getId(), e.getMessage());
                cleanupDeadSession(session);
            }
        }
    }
}
