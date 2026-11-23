package com.example.comparator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Keeps the layers one-directional: controller → service → client / comparator → model, config at the side. */
class LayeringTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/comparator");
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?com\\.example\\.comparator\\.(\\w+)\\.",
            Pattern.MULTILINE);

    /** Layer → layers it may use. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "model", Set.of(),
            "comparator", Set.of("model", "config"),
            "config", Set.of("model"),
            "client", Set.of("config", "model"),
            "service", Set.of("client", "comparator", "config", "model"),
            "controller", Set.of("service", "client", "model", "config"));

    @Test
    void layersOnlyDependOnTheLayersBelowThem() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String layer = ROOT.relativize(file).getName(0).toString();
                if (!ALLOWED.containsKey(layer)) {
                    continue; // the application class sits at the root
                }
                Matcher imports = IMPORT.matcher(Files.readString(file));
                while (imports.find()) {
                    String used = imports.group(1);
                    if (!used.equals(layer) && !ALLOWED.get(layer).contains(used)) {
                        violations.add(file.getFileName() + ": " + layer + " must not use " + used);
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
