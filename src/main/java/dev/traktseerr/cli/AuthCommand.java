package dev.traktseerr.cli;

import dev.traktseerr.client.TraktClient;
import dev.traktseerr.config.Settings;
import picocli.CommandLine.Command;

@Command(
    name        = "auth",
    description = "Authorize with Trakt via the device-flow OAuth. Run once before the first sync."
)
public class AuthCommand implements Runnable {
    @Override
    public void run() {
        Settings cfg = Settings.fromEnv();
        if (cfg.traktClientId().isBlank() || cfg.traktClientSecret().isBlank()) {
            System.err.println("ERROR: TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET must be set.");
            System.exit(1);
        }
        try {
            new TraktClient(cfg).deviceAuthorize();
        } catch (Exception e) {
            System.err.println("Auth failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
