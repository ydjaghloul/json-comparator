package com.example.comparator.comparator;

import com.example.comparator.model.CompareOptions;
import com.example.comparator.model.Difference;
import com.example.comparator.model.DifferenceType;
import com.example.comparator.model.NullMatching;
import com.example.comparator.model.TypeMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.example.comparator.config.ComparatorProperties.Comparison;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Swapping source and target must give the mirrored result: same differences, source/target sides exchanged. */
class ComparatorSymmetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonComparator comparator = new JsonComparator(new Comparison(null, null, null, null, null));

    /** Side-independent form of a difference: array indexes are side specific, so they are dropped. */
    private static String canonical(Difference d, boolean mirrored) {
        DifferenceType type = d.type();
        JsonNode source = d.source();
        JsonNode target = d.target();
        if (mirrored) {
            type = switch (type) {
                case MISSING_IN_SOURCE -> DifferenceType.MISSING_IN_TARGET;
                case MISSING_IN_TARGET -> DifferenceType.MISSING_IN_SOURCE;
                default -> type;
            };
            JsonNode swap = source;
            source = target;
            target = swap;
        }
        return d.jsonPath().replaceAll("\\[\\d+]", "[]") + "|" + type + "|" + source + "|" + target;
    }

    private List<String> forward(JsonNode a, JsonNode b, CompareOptions options) {
        return comparator.compare(a, b, options).stream().map(d -> canonical(d, false)).sorted().toList();
    }

    private List<String> backward(JsonNode a, JsonNode b, CompareOptions options) {
        return comparator.compare(b, a, options).stream().map(d -> canonical(d, true)).sorted().toList();
    }

    @Test
    void swappingSourceAndTargetGivesTheMirroredResult() {
        Random random = new Random(42);
        List<CompareOptions> variants = List.of(new CompareOptions(null, null),
                new CompareOptions(TypeMatching.LENIENT, NullMatching.LENIENT),
                new CompareOptions(null, null, true, null),
                new CompareOptions(null, null, null, List.of("code", "id")));
        for (int i = 0; i < 3000; i++) {
            JsonNode a = randomArray(random);
            JsonNode b = random.nextInt(4) == 0 ? mutate(a, random) : randomArray(random);
            for (CompareOptions options : variants) {
                assertThat(backward(a, b, options)).as("a=%s b=%s options=%s", a, b, options)
                        .isEqualTo(forward(a, b, options));
            }
        }
    }

    private static JsonNode mutate(JsonNode array, Random random) {
        ArrayNode copy = array.deepCopy();
        List<JsonNode> elements = new ArrayList<>();
        copy.forEach(elements::add);
        java.util.Collections.shuffle(elements, random);
        ArrayNode out = MAPPER.createArrayNode();
        elements.forEach(out::add);
        if (!out.isEmpty() && random.nextBoolean() && out.get(0) instanceof ObjectNode o) {
            o.put("v", random.nextInt(3));
        }
        return out;
    }

    private static ArrayNode randomArray(Random random) {
        ArrayNode array = MAPPER.createArrayNode();
        int size = random.nextInt(5);
        for (int i = 0; i < size; i++) {
            ObjectNode o = MAPPER.createObjectNode();
            if (random.nextInt(6) != 0) {
                o.put("id", random.nextInt(4)); // small range: duplicates happen
            }
            if (random.nextInt(3) == 0) {
                o.put("code", "c" + random.nextInt(4));
            }
            if (random.nextBoolean()) {
                o.put("v", random.nextInt(3));
            }
            if (random.nextInt(4) == 0) {
                o.set("sub", randomScalars(random));
            }
            if (random.nextInt(8) == 0) {
                o.putNull("n");
            }
            if (random.nextInt(10) == 0) {
                o.put("id", String.valueOf(random.nextInt(4)));
            }
            array.add(o);
        }
        return array;
    }

    private static ArrayNode randomScalars(Random random) {
        ArrayNode array = MAPPER.createArrayNode();
        for (int i = random.nextInt(4); i > 0; i--) {
            array.add(random.nextInt(3));
        }
        return array;
    }
}
