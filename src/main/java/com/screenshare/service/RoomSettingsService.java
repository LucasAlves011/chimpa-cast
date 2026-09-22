package com.screenshare.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RoomSettingsService {

    private static final Logger log = LoggerFactory.getLogger(RoomSettingsService.class);

    // Flag configurável: permite ou não que usuários encerrem telas de outros
    private final AtomicBoolean allowRemoteScreenKick;

    public RoomSettingsService(@Value("${app.allow-remote-kick:true}") boolean defaultAllowRemoteKick) {
        this.allowRemoteScreenKick = new AtomicBoolean(defaultAllowRemoteKick);
    }

    public boolean isAllowRemoteScreenKick() {
        return allowRemoteScreenKick.get();
    }

    public void setAllowRemoteScreenKick(boolean enabled) {
        boolean previous = allowRemoteScreenKick.getAndSet(enabled);
        log.info("Configuração de Encerramento Remoto de Telas alterada: {} -> {}", previous, enabled);
    }
}
