package de.wstein.flixplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Retains the latest compiler-owned source coverage snapshot for one project. */
public final class FlixCoverageService {
    public enum State { COVERED, UNCOVERED, PARTIAL }

    public static final class Line {
        private final State state;
        private final int hitCount;

        private Line(State state, int hitCount) {
            this.state = state;
            this.hitCount = hitCount;
        }

        public State getState() { return state; }
        public int getHitCount() { return hitCount; }
    }

    static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(Map.of());
        private final Map<String, Map<Integer, Line>> files;

        private Snapshot(Map<String, Map<Integer, Line>> files) {
            this.files = files;
        }

        static Snapshot parse(@NotNull String json, boolean eventPartial, @Nullable Path projectRoot) {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean partial = eventPartial || booleanValue(root, "partial");
            Map<String, Map<Integer, Line>> files = new HashMap<>();
            JsonArray sourceFiles = arrayValue(root, "files");
            for (JsonElement sourceElement : sourceFiles) {
                JsonObject source = sourceElement.getAsJsonObject();
                String path = source.get("path").getAsString();
                Map<Integer, Line> lines = new HashMap<>();
                for (JsonElement lineElement : arrayValue(source, "lines")) {
                    JsonObject line = lineElement.getAsJsonObject();
                    int number = line.get("line").getAsInt();
                    int hits = line.has("hitCount") ? line.get("hitCount").getAsInt() : 0;
                    boolean covered = line.has("covered") && line.get("covered").getAsBoolean();
                    State state = partial ? State.PARTIAL : covered ? State.COVERED : State.UNCOVERED;
                    lines.put(number, new Line(state, hits));
                }
                files.put(normalize(path, projectRoot), Collections.unmodifiableMap(lines));
            }
            return new Snapshot(Collections.unmodifiableMap(files));
        }

        @Nullable Line line(@NotNull String path, int oneBasedLine) {
            // A VirtualFile's path is always absolute, so it needs no project root to resolve against.
            Map<Integer, Line> lines = files.get(normalize(path, null));
            return lines == null ? null : lines.get(oneBasedLine);
        }

        private static JsonArray arrayValue(JsonObject object, String name) {
            JsonElement value = object.get(name);
            return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
        }

        private static boolean booleanValue(JsonObject object, String name) {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() && value.getAsBoolean();
        }

        /**
         * A relative compiler-reported path is resolved against the project root, not the JVM's
         * working directory -- in Split Mode {@code backend} runs host-side, where that directory
         * is not the project's, so resolving against it would silently make every relative path
         * fail to match its {@code VirtualFile} and leave the gutter unmarked.
         */
        private static String normalize(String path, @Nullable Path projectRoot) {
            try {
                Path candidate = Path.of(path);
                Path resolved = candidate.isAbsolute() || projectRoot == null
                        ? candidate
                        : projectRoot.resolve(candidate);
                return resolved.normalize().toString();
            } catch (InvalidPathException ignored) {
                return path;
            }
        }
    }

    private final Project project;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public FlixCoverageService(@NotNull Project project) {
        this.project = project;
    }

    public void accept(@NotNull String coverageJson, boolean partial) {
        String basePath = project.getBasePath();
        snapshot = Snapshot.parse(coverageJson, partial, basePath == null ? null : Path.of(basePath));
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) DaemonCodeAnalyzer.getInstance(project).restart();
        });
    }

    public @Nullable Line line(@NotNull VirtualFile file, int oneBasedLine) {
        return snapshot.line(file.getPath(), oneBasedLine);
    }
}
