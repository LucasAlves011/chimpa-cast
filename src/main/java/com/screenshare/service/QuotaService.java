package com.screenshare.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screenshare.websocket.RoomWebSocketHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final long FREE_TIER_BYTES = 1_000_000_000_000L; // 1 TB = 1.000 GB
    private static final File QUOTA_FILE = new File("quota-storage.json");

    @Value("${cloudflare.calls.account-id:}")
    private String accountId;

    @Value("${cloudflare.calls.analytics-token:${cloudflare.calls.app-secret:}}")
    private String analyticsToken;

    @Value("${quota.hard-limit-bytes:990000000000}")
    private long hardLimitBytes;

    @Value("${quota.warning-limit-bytes:900000000000}")
    private long warningLimitBytes;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RoomWebSocketHandler roomWebSocketHandler;
    private final HttpClient httpClient;

    private final AtomicLong usedBytes = new AtomicLong(0);
    private String currentMonth = "";

    public QuotaService(RoomWebSocketHandler roomWebSocketHandler) {
        this.roomWebSocketHandler = roomWebSocketHandler;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isQuotaAvailable() {
        return usedBytes.get() < hardLimitBytes;
    }

    public boolean isWarningThreshold() {
        return usedBytes.get() >= warningLimitBytes;
    }

    @PostConstruct
    public void init() {
        this.currentMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        loadQuotaFromDisk();
        syncWithCloudflareGraphQL();
        log.info("QuotaService iniciado para o mês [{}]. Consumo atual: {} GB | Teto seguro: {} GB",
                currentMonth, getUsedGb(), hardLimitBytes / 1_000_000_000.0);
    }

    /**
     * A cada 5 segundos, acumula os bytes transmitidos na sala ativa
     * e transmite a cota atualizada via WebSocket com Kill-Switch
     */
    @Scheduled(fixedRate = 5000)
    public void tickEgress() {
        checkMonthRollover();

        RoomWebSocketHandler.ActiveRoomStats stats = roomWebSocketHandler.calculateCurrentEgressRate();
        if (stats.currentRateBytesPerSecond() > 0) {
            long addedBytes = (long) (stats.currentRateBytesPerSecond() * 5.0);
            usedBytes.addAndGet(addedBytes);
            saveQuotaToDisk();
        }

        // Se estourar o limite de segurança de 990 GB: KILL-SWITCH IMEDIATO!
        if (!isQuotaAvailable()) {
            log.warn("KILL-SWITCH ACIONADO: Cota atingiu o teto seguro de {} bytes! Desconectando todos os participantes...", hardLimitBytes);
            roomWebSocketHandler.forceDisconnectAllForQuota("COTA_ESGOTADA: O limite seguro da cota gratuita (990 GB) foi atingido. Para sua segurança e garantia de custo zero, todas as transmissões foram encerradas até o próximo mês!");
            return;
        }

        // Envia cota atualizada para todos os navegadores conectados
        Map<String, Object> data = getQuotaData();
        data.put("warningNearLimit", isWarningThreshold());
        roomWebSocketHandler.broadcastQuotaUpdate(data);
    }

    /**
     * A cada 3 minutos, sincroniza com a API GraphQL oficial da Cloudflare
     */
    @Scheduled(fixedRate = 180000)
    public boolean syncWithCloudflareGraphQL() {
        if (accountId == null || accountId.isBlank() || analyticsToken == null || analyticsToken.isBlank()) {
            return false;
        }

        try {
            YearMonth ym = YearMonth.now();
            String startDate = ym.atDay(1).toString() + "T00:00:00Z";
            String endDate = ym.atEndOfMonth().toString() + "T23:59:59Z";

            String query = """
                {"query":"query GetCallsUsage { viewer { accounts(filter: { accountTag: \\"%s\\" }) { callsUsageAdaptiveGroups(filter: { datetime_geq: \\"%s\\", datetime_leq: \\"%s\\" }, limit: 10) { sum { egressBytes } } } } }"}
                """.formatted(accountId, startDate, endDate);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cloudflare.com/client/v4/graphql"))
                    .header("Authorization", "Bearer " + analyticsToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(query))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode sumNode = root.path("data").path("viewer").path("accounts")
                        .path(0).path("callsUsageAdaptiveGroups").path(0).path("sum");

                if (!sumNode.isMissingNode() && sumNode.has("egressBytes")) {
                    long officialEgress = sumNode.path("egressBytes").asLong(0);
                    log.info("Sincronizado com sucesso via GraphQL oficial da Cloudflare: {} bytes ({} GB)",
                            officialEgress, Math.round((officialEgress / 1_000_000_000.0) * 100.0) / 100.0);
                    if (officialEgress > usedBytes.get()) {
                        usedBytes.set(officialEgress);
                        saveQuotaToDisk();
                    }
                    return true;
                }
            } else {
                log.warn("Falha ao consultar GraphQL da Cloudflare: HTTP {} - {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Erro ao sincronizar com GraphQL da Cloudflare: {}", e.getMessage());
        }
        return false;
    }

    public Map<String, Object> getQuotaData() {
        long used = usedBytes.get();
        long remaining = Math.max(0, FREE_TIER_BYTES - used);
        double pctRemaining = (double) remaining / FREE_TIER_BYTES * 100.0;
        double pctUsed = (double) used / FREE_TIER_BYTES * 100.0;

        RoomWebSocketHandler.ActiveRoomStats stats = roomWebSocketHandler.calculateCurrentEgressRate();
        double currentGbPerHour = (stats.currentRateBytesPerSecond() * 3600.0) / 1_000_000_000.0;

        Map<String, Object> data = new HashMap<>();
        data.put("totalBytes", FREE_TIER_BYTES);
        data.put("usedBytes", used);
        data.put("remainingBytes", remaining);
        data.put("usedGb", Math.round((used / 1_000_000_000.0) * 1000.0) / 1000.0);
        data.put("usedMb", Math.round((used / 1_000_000.0) * 10.0) / 10.0);
        data.put("remainingGb", Math.round((remaining / 1_000_000_000.0) * 100.0) / 100.0);
        data.put("percentageRemaining", Math.round(pctRemaining * 100.0) / 100.0);
        data.put("percentageUsed", Math.round(pctUsed * 100.0) / 100.0);
        data.put("activeScreens", stats.totalActiveScreens());
        data.put("activeViewers", stats.totalActiveViewers());
        data.put("currentRateGbPerHour", Math.round(currentGbPerHour * 10.0) / 10.0);
        data.put("month", currentMonth);

        return data;
    }

    private void checkMonthRollover() {
        String thisMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        if (!thisMonth.equals(currentMonth)) {
            log.info("Virada de mês detectada ({} -> {}). Resetando cota gratuita para 1 TB!", currentMonth, thisMonth);
            currentMonth = thisMonth;
            usedBytes.set(0);
            saveQuotaToDisk();
        }
    }

    private void loadQuotaFromDisk() {
        try {
            if (QUOTA_FILE.exists()) {
                String content = Files.readString(QUOTA_FILE.toPath());
                JsonNode json = objectMapper.readTree(content);
                String savedMonth = json.path("month").asText("");
                if (savedMonth.equals(currentMonth)) {
                    usedBytes.set(json.path("usedBytes").asLong(0));
                } else {
                    usedBytes.set(0);
                }
            }
        } catch (Exception e) {
            log.warn("Não foi possível carregar quota-storage.json: {}", e.getMessage());
        }
    }

    private synchronized void saveQuotaToDisk() {
        try {
            Map<String, Object> map = Map.of(
                    "month", currentMonth,
                    "usedBytes", usedBytes.get(),
                    "lastUpdated", System.currentTimeMillis()
            );
            objectMapper.writeValue(QUOTA_FILE, map);
        } catch (IOException e) {
            log.warn("Erro ao salvar quota-storage.json: {}", e.getMessage());
        }
    }

    public double getUsedGb() {
        return Math.round((usedBytes.get() / 1_000_000_000.0) * 100.0) / 100.0;
    }

    public void resetQuota() {
        usedBytes.set(0);
        saveQuotaToDisk();
    }
}
