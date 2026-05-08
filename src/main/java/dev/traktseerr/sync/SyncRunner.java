package dev.traktseerr.sync;

import dev.traktseerr.client.MalClient;
import dev.traktseerr.client.SeerrClient;
import dev.traktseerr.client.SeerrClient.SeerrRequestException;
import dev.traktseerr.client.TraktClient;
import dev.traktseerr.config.Settings;
import dev.traktseerr.model.MediaItem;
import dev.traktseerr.model.TraktTokens;
import dev.traktseerr.state.StateManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Main sync orchestrator.
 * Mirrors sync.py: iterates all sources, calls Seerr for each item, persists state.
 */
public class SyncRunner {

    private final Settings cfg;

    public SyncRunner(Settings cfg) {
        this.cfg = cfg;
    }

    /**
     * Run the sync. Returns 0 on success, 2 if any Seerr errors occurred.
     */
    public int run(boolean dryRun, boolean force) {
        if (!cfg.traktSyncEnabled() && !cfg.malSyncEnabled()) {
            System.err.println("ERROR: No sync source enabled. "
                               + "Set TRAKT_SYNC_ENABLED=true or MAL_SYNC_ENABLED=true.");
            return 1;
        }
        if (cfg.seerrApiKey().isBlank()) {
            System.err.println("ERROR: SEERR_API_KEY is not set.");
            return 1;
        }

        StateManager state = new StateManager(cfg.stateDbPath());
        TraktClient  trakt = new TraktClient(cfg);
        SeerrClient  seerr = new SeerrClient(cfg);

        List<MediaItem> items = new ArrayList<>();

        // --- Trakt source ---
        if (cfg.traktSyncEnabled()) {
            try {
                TraktTokens tokens = trakt.ensureValidTokens();
                List<MediaItem> watchlist = trakt.fetchWatchlist(tokens);
                System.out.println("[Trakt] Fetched " + watchlist.size() + " watchlist item(s).");
                items.addAll(watchlist);
            } catch (Exception e) {
                System.err.println("ERROR: Trakt sync failed: " + e.getMessage());
                return 1;
            }
        }

        // --- MAL source ---
        if (cfg.malSyncEnabled()) {
            if (cfg.tmdbApiKey().isBlank()) {
                System.err.println("ERROR: TMDB_API_KEY is required for MAL sync.");
                return 1;
            }
            try {
                MalClient mal = new MalClient(cfg);
                List<MediaItem> animeItems = mal.fetchAnimeList();
                System.out.println("[MAL] Fetched " + animeItems.size() + " mapped anime item(s).");
                items.addAll(animeItems);
            } catch (Exception e) {
                System.err.println("ERROR: MAL sync failed: " + e.getMessage());
                return 1;
            }
        }

        // --- Process items ---
        int requested  = 0;
        int skipped    = 0;
        int duplicates = 0;
        int errors     = 0;

        for (MediaItem item : items) {
            if (state.shouldSkip(item.mediaType(), item.tmdbId(), force)) {
                skipped++;
                continue;
            }

            String label = "[" + item.mediaType() + "] " + item.title()
                           + " (TMDB:" + item.tmdbId() + ")";

            if (dryRun) {
                System.out.println("DRY-RUN: would request " + label);
                continue;
            }

            try {
                seerr.requestMedia(item.mediaType(), item.tmdbId());
                System.out.println("Requested: " + label);
                state.upsert(item.mediaType(), item.tmdbId(), "requested", item.title());
                requested++;
            } catch (SeerrRequestException e) {
                if (e.statusCode == 409) {
                    System.out.println("Duplicate (already in Seerr): " + label);
                    state.upsert(item.mediaType(), item.tmdbId(), "duplicate", item.title());
                    duplicates++;
                } else if (e.statusCode == 403) {
                    System.err.println("WARN: Forbidden for " + label);
                    state.upsert(item.mediaType(), item.tmdbId(), "error_forbidden", item.title());
                    errors++;
                } else {
                    System.err.println("ERROR: Seerr HTTP " + e.statusCode + " for " + label);
                    state.upsert(item.mediaType(), item.tmdbId(), "error",
                                 "HTTP " + e.statusCode + " — " + item.title());
                    errors++;
                }
            } catch (Exception e) {
                System.err.println("ERROR: Request failed for " + label + ": " + e.getMessage());
                state.upsert(item.mediaType(), item.tmdbId(), "error",
                             e.getMessage() + " — " + item.title());
                errors++;
            }
        }

        System.out.printf("Done: %d requested, %d skipped, %d duplicates, %d errors%n",
                          requested, skipped, duplicates, errors);
        return errors > 0 ? 2 : 0;
    }
}
