package dev.traktseerr.cli;

import dev.traktseerr.config.Settings;
import dev.traktseerr.state.StateManager;
import picocli.CommandLine.Command;

import java.util.Scanner;

@Command(
    name        = "clear-state",
    description = "Delete the local SQLite deduplication database."
)
public class ClearStateCommand implements Runnable {
    @Override
    public void run() {
        Settings cfg = Settings.fromEnv();
        System.out.println("This will delete: " + cfg.stateDbPath().toAbsolutePath());
        System.out.print("Type 'yes' to confirm: ");
        System.out.flush();
        String answer = new Scanner(System.in).nextLine().strip();
        if ("yes".equalsIgnoreCase(answer)) {
            StateManager.clearStateFile(cfg.stateDbPath());
            System.out.println("State cleared.");
        } else {
            System.out.println("Aborted.");
        }
    }
}
