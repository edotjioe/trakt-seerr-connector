package dev.traktseerr;

import dev.traktseerr.cli.MainCommand;
import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        // Load H2 driver explicitly so GraalVM native image sees it
        try { Class.forName("org.h2.Driver"); } catch (ClassNotFoundException ignored) {}

        int exitCode = new CommandLine(new MainCommand()).execute(args);
        System.exit(exitCode);
    }
}
