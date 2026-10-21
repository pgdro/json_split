/* Copyright 2026 Daniel-Gheorghe Popiniuc */
package io.github.pgdro.json_split.cli;

import io.github.pgdro.tools.core.InteractiveInputClass;
import picocli.CommandLine;

/**
 * Main Command Line
 */
@CommandLine.Command(
    name = "top",
    subcommands = {
            JsonSplit.class
    }
)
public final class ApplicationJsonSplit {

    /**
     * Constructor empty
     */
    private ApplicationJsonSplit() {
        super();
    }

    /**
     * Constructor
     *
     * @param args command-line arguments
     */
    /* default */ static void main(final String... args) {
        InteractiveInputClass.startMeUpWithParameters("logs/JsonSplit-", "/json-split-pom.xml");
        final int intJsonExitCode = new CommandLine(new ApplicationJsonSplit()).execute(args);
        InteractiveInputClass.shutMeDownWithParameters(intJsonExitCode, args[0]);
    }

}
