package com.screenshare.service;

/**
 * Identidade verificada de um membro do servidor do Discord.
 */
public record DiscordUser(String id, String name, String avatarUrl) {}
