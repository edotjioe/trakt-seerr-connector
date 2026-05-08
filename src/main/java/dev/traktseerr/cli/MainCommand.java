package dev.traktseerr.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name            = "trakt-seerr-connector",
    version         = "1.0.0",
    description     = "Sync Trakt / MyAnimeList watchlists to Seerr requests.",
    mixinStandardHelpOptions = true,
    subcommands     = {AuthCommand.class, SyncCommand.class, ClearStateCommand.class}
)
public class MainCommand implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
