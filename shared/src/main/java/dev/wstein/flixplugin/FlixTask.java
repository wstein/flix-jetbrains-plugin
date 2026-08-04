package dev.wstein.flixplugin;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Optional;

/**
 * A Flix CLI subcommand this plugin can run as a task.
 *
 * <p>Lives in {@code shared}, beside {@link FlixLaunchCommand}, for the same reason: the invocation
 * is one fact, and the modules that need it cannot depend on each other. The run configuration and
 * the Tools menu both enumerate this rather than listing subcommands of their own, so adding one
 * here adds it everywhere — and a name that does not exist cannot be added in only one of them.
 *
 * <p>Deliberately a subset. {@code lsp} and {@code lsp-vscode} are servers the plugin starts
 * itself; {@code repl} needs a terminal this does not give it; {@code format} would fight the
 * editor's own formatter, which the language server already provides; {@code release},
 * {@code eff-lock} and the {@code X}-prefixed commands are not project workflow. Every name here is
 * checked against the compiler's own parser by {@code FlixTaskTest}.
 */
public enum FlixTask {

    /** Checks the project for errors, without producing class files. */
    CHECK("check", "Check", "Check the project for errors"),

    /** Compiles the project. */
    BUILD("build", "Build", "Compile the project"),

    /** Runs the project's tests. */
    TEST("test", "Test", "Run the project's tests"),

    /**
     * Runs the project's main function.
     *
     * <p>Not the same thing as the Flix run/debug configuration, which exists to attach a debugger
     * and therefore needs the Java plugin. This is the plain CLI run, available wherever the plugin
     * loads.
     */
    RUN("run", "Run", "Run the project's main function"),

    /** Removes class files from the build directory. */
    CLEAN("clean", "Clean", "Remove class files from the build directory"),

    /** Generates API documentation. */
    DOC("doc", "Generate Documentation", "Generate API documentation for the project"),

    /** Builds a jar of the project. */
    BUILD_JAR("build-jar", "Build Jar", "Build a jar file from the project"),

    /** Builds a jar of the project and its dependencies. */
    BUILD_FATJAR("build-fatjar", "Build Fat Jar", "Build a fatjar file from the project"),

    /** Builds a Flix package of the project. */
    BUILD_PKG("build-pkg", "Build Package", "Build an fpkg file from the project"),

    /** Reports dependencies with newer versions available. */
    OUTDATED("outdated", "Show Outdated Dependencies", "Show dependencies with newer versions available"),

    /** Creates a new project in the project directory. */
    INIT("init", "Init", "Create a new Flix project in the project directory");

    private final String command;
    private final String title;
    private final String description;

    FlixTask(@NotNull String command, @NotNull String title, @NotNull String description) {
        this.command = command;
        this.title = title;
        this.description = description;
    }

    /** The subcommand as the compiler's argument parser spells it. */
    public @NotNull String command() {
        return command;
    }

    /** What a menu item or a run configuration calls this. */
    public @NotNull String title() {
        return title;
    }

    /** One line saying what running it does, for a tooltip or a configuration's description. */
    public @NotNull String description() {
        return description;
    }

    /**
     * The task named by {@code command}, or empty if there is none.
     *
     * <p>Empty rather than a default: the value comes back from a persisted run configuration, and a
     * task that was renamed or removed must not silently become a different one — which, for
     * {@code clean} or {@code build-jar}, would run something destructive that nobody asked for.
     */
    public static @NotNull Optional<FlixTask> byCommand(String command) {
        return Arrays.stream(values()).filter(task -> task.command.equals(command)).findFirst();
    }
}
