package dev.traktseerr.model;

/**
 * OAuth tokens for the Trakt API.
 * expiresAt is a Unix epoch second (long).
 */
public record TraktTokens(String accessToken, String refreshToken, long expiresAt) {}
