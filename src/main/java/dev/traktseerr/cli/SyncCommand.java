package dev.traktseerr.cli;

import dev.traktseerr.config.Settings;
import dev.traktseerr.sync.SyncRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name        = "sync",
    description = "Sync watchlists to Seerr."
)
public class SyncCommand implements Runnable {

    @Option(names = "--dry-run",
            description = "Log what would be requested without calling Seerr.")
    boolean dryRun;

    @Option(names = "--force",
            description = "Re-process items that were already synced.")
    boolean force;

    @Override
    public void run() {
        Settings cfg  = Settings.fromEnv();
        int exitCode  = new SyncRunner(cfg).run(dryRun, force);
        System.exit(exitCode);
    }
}
