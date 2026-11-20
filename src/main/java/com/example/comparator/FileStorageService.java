package com.example.comparator;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Saves live comparisons under {@code {output.directory}/{yyyyMMdd-HHmmss}/}: one folder per path holding the
 * two responses and the report of that path, plus the report of the whole run. Only response bodies and reports
 * are ever written.
 */
@Service
public class FileStorageService {

    /** Files written in the folder of each path: {@code {run}/{name}/source.json} etc. */
    static final String SOURCE_FILE = "source.json";
    static final String TARGET_FILE = "target.json";
    static final String REPORT_FILE = "report.json";

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final DateTimeFormatter RUN_FOLDER_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^A-Za-z0-9._-]");

    private final Path root;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FileStorageService(ComparatorProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.root = Path.of(properties.output().directory()).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * File name stem for a path: leading {@code /} removed, every character outside {@code [A-Za-z0-9._-]} replaced
     * by {@code _}, {@code root} when nothing is left. {@code /customers/42/orders -> customers_42_orders}.
     */
    public static String sanitize(String path) {
        String value = path == null ? "" : path;
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        String name = UNSAFE_CHARACTERS.matcher(value).replaceAll("_");
        return name.isEmpty() ? "root" : name;
    }

    /** Creates the folder for this run. On failure, saving is disabled for the run but comparison goes on. */
    public RunFolder startRun() {
        String name = LocalDateTime.now(clock).format(RUN_FOLDER_FORMAT);
        try {
            Files.createDirectories(root);
            Path folder = root.resolve(name);
            for (int attempt = 2; ; attempt++) {
                try {
                    Files.createDirectory(folder);
                    log.info("Saving responses to {}", folder);
                    return new RunFolder(folder);
                }
                catch (FileAlreadyExistsException e) {
                    // Two runs within the same second: never overwrite the earlier one.
                    folder = root.resolve(name + "-" + attempt);
                }
            }
        }
        catch (IOException | RuntimeException e) {
            log.warn("Could not create run folder under {}, responses will not be saved: {}", root, e.toString());
            return new RunFolder(null);
        }
    }

    /**
     * Writes {@code body} as pretty-printed JSON into the run folder.
     *
     * @param fileName file name relative to the run folder, may include a sub folder ({@code orders/source.json});
     *                 missing folders are created
     * @return the file path relative to {@code output.directory}, or empty if nothing was saved
     */
    public Optional<String> save(RunFolder run, String fileName, Object body) {
        if (run.folder() == null || body == null) {
            return Optional.empty();
        }
        Path file = run.folder().resolve(fileName);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), body);
            return Optional.of(relativize(file));
        }
        catch (IOException | RuntimeException e) {
            log.warn("Could not save {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Path relative to {@code output.directory}, always with {@code /} separators. */
    public String relativize(Path file) {
        if (file == null) {
            return null;
        }
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
    }

    public Path root() {
        return root;
    }

    /** Folder of one run; {@code folder} is null when it could not be created. Tracks names used in the run. */
    public static final class RunFolder {

        private final Path folder;
        private final Set<String> usedNames = new HashSet<>();

        RunFolder(Path folder) {
            this.folder = folder;
        }

        public Path folder() {
            return folder;
        }

        /**
         * Returns the name stem for a path, adding {@code _2}, {@code _3}... when two paths sanitize to the same
         * name in one run (e.g. {@code /a/b} and {@code /a_b}), so no response is overwritten.
         */
        public synchronized String reserveName(String path) {
            String base = sanitize(path);
            String name = base;
            for (int i = 2; !usedNames.add(name); i++) {
                name = base + "_" + i;
            }
            return name;
        }
    }
}
