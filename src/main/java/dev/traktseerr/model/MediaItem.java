package dev.traktseerr.model;

/**
 * A single media item resolved to a TMDB ID, ready to be requested in Seerr.
 * mediaType is either "movie" or "tv".
 */
public record MediaItem(String mediaType, int tmdbId, String title) {}
