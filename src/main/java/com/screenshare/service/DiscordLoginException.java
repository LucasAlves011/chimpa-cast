package com.screenshare.service;

/**
 * Falha no login via Discord. O código é repassado ao front (ex: not_member, missing_role, discord_error).
 */
public class DiscordLoginException extends Exception {

    private final String code;

    public DiscordLoginException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
