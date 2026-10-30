package com.screenshare.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * Mantém uma conexão do bot com o Gateway do Discord só para saber quem está em canais de voz.
 * A REST API não lista os membros de um canal de voz; o Gateway (intent GUILD_VOICE_STATES, não privilegiada) sim.
 */
@Service
public class DiscordVoiceService {

    private static final Logger log = LoggerFactory.getLogger(DiscordVoiceService.class);

    @Value("${discord.bot-token:}")
    private String botToken;

    @Value("${discord.guild-id:}")
    private String guildId;

    @Value("${discord.notify-mention-voice:true}")
    private boolean mentionVoice;

    private volatile JDA jda;

    @PostConstruct
    void start() {
        if (!mentionVoice || isBlank(botToken) || isBlank(guildId)) {
            return;
        }
        // Conecta em segundo plano para não atrasar nem derrubar a subida da aplicação
        Thread starter = new Thread(() -> {
            try {
                JDA instance = JDABuilder.createLight(botToken, EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                        .enableCache(CacheFlag.VOICE_STATE)
                        .setMemberCachePolicy(MemberCachePolicy.VOICE)
                        .setChunkingFilter(ChunkingFilter.NONE)
                        .build();
                instance.awaitReady();
                jda = instance;
                log.info("Bot conectado ao Gateway do Discord para consultar quem está em canais de voz.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Não foi possível conectar o bot ao Gateway do Discord; avisos não marcarão o pessoal da call: {}", e.getMessage());
            }
        }, "discord-gateway-starter");
        starter.setDaemon(true);
        starter.start();
    }

    @PreDestroy
    void stop() {
        JDA instance = jda;
        if (instance != null) {
            instance.shutdownNow();
        }
    }

    /**
     * IDs de quem está em qualquer canal de voz/palco do servidor agora, exceto bots e o usuário excluído.
     * Retorna lista vazia se o bot ainda não conectou ao Gateway.
     */
    public List<String> getVoiceMemberIds(String excludeUserId) {
        JDA instance = jda;
        if (instance == null) return Collections.emptyList();

        Guild guild = instance.getGuildById(guildId);
        if (guild == null) {
            log.warn("Bot não encontrou o servidor {}; verifique se ele foi convidado.", guildId);
            return Collections.emptyList();
        }

        return guild.getMemberCache().stream()
                .filter(m -> !m.getUser().isBot())
                .filter(m -> !m.getId().equals(excludeUserId))
                .filter(DiscordVoiceService::isInVoice)
                .map(Member::getId)
                .toList();
    }

    private static boolean isInVoice(Member member) {
        GuildVoiceState state = member.getVoiceState();
        return state != null && state.inAudioChannel();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
