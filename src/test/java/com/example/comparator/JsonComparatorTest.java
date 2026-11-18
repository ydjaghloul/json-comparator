package com.example.comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.comparator.ComparatorProperties.ArrayOrder;
import com.example.comparator.ComparatorProperties.Comparison;
import com.example.comparator.ComparatorProperties.UnorderedArray;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JsonComparatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text.replace('\'', '"'));
        }
        catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<Difference> compare(Comparison settings, String source, String target) {
        return new JsonComparator(settings).compare(json(source), json(target));
    }

    private static List<Difference> compare(String source, String target) {
        return compare(new Comparison(null, null, null, null, null), source, target);
    }

    @Test
    void identicalDocumentsHaveNoDifferences() {
        assertThat(compare("{'a':1,'b':[1,2],'c':{'d':null}}", "{'a':1,'b':[1,2],'c':{'d':null}}")).isEmpty();
    }

    @Test
    void propertyOrderIsIgnored() {
        assertThat(compare("{'total':100,'items':[{'sku':'A','qty':1}],'id':7}",
                "{'id':7,'items':[{'qty':1,'sku':'A'}],'total':100}")).isEmpty();
    }

    @Test
    void valueMismatchCarriesPathBothValuesAndMessage() {
        List<Difference> differences = compare("{'total':100}", "{'total':105}");

        assertThat(differences).singleElement().satisfies(d -> {
            assertThat(d.jsonPath()).isEqualTo("$.total");
            assertThat(d.type()).isEqualTo(DifferenceType.VALUE_MISMATCH);
            assertThat(d.source().intValue()).isEqualTo(100);
            assertThat(d.target().intValue()).isEqualTo(105);
            assertThat(d.message()).isEqualTo("$.total: source=100, target=105");
        });
    }

    @Test
    void numbersCompareByValue() {
        assertThat(compare("{'a':1,'b':2.50}", "{'a':1.0,'b':2.5}")).isEmpty();
    }

    @Test
    void typeMatters() {
        List<Difference> differences = compare("{'id':1}", "{'id':'1'}");

        assertThat(differences).singleElement().satisfies(d -> {
            assertThat(d.jsonPath()).isEqualTo("$.id");
            assertThat(d.type()).isEqualTo(DifferenceType.TYPE_MISMATCH);
            assertThat(d.source().isNumber()).isTrue();
            assertThat(d.target().isTextual()).isTrue();
            assertThat(d.message()).contains("number").contains("string");
        });
    }

    @Test
    void nullIsNotTheSameAsMissing() {
        List<Difference> differences = compare("{'a':null}", "{}");

        assertThat(differences).singleElement().satisfies(d -> {
            assertThat(d.jsonPath()).isEqualTo("$.a");
            assertThat(d.type()).isEqualTo(DifferenceType.MISSING_IN_TARGET);
            assertThat(d.source().isNull()).isTrue();
            assertThat(d.target()).isNull();
        });
        assertThat(compare("{'a':null}", "{'a':0}")).singleElement()
                .extracting(Difference::type).isEqualTo(DifferenceType.TYPE_MISMATCH);
    }

    @Test
    void missingFieldsAreReportedOnBothSides() {
        List<Difference> differences = compare("{'items':[{'sku':'A','discount':5}]}",
                "{'items':[{'sku':'A','tax':2}]}");

        assertThat(differences).extracting(Difference::jsonPath, Difference::type).containsExactly(
                tuple("$.items[0].discount", DifferenceType.MISSING_IN_TARGET),
                tuple("$.items[0].tax", DifferenceType.MISSING_IN_SOURCE));
        assertThat(differences.get(0).message())
                .isEqualTo("$.items[0].discount: present in source (5), missing in target");
        assertThat(differences.get(1).message())
                .isEqualTo("$.items[0].tax: missing in source, present in target (2)");
    }

    @Test
    void missingObjectValueIsShownAsJson() {
        List<Difference> differences = compare("{'a':{'b':[1,2],'c':'x'}}", "{}");

        assertThat(differences).singleElement().satisfies(d -> {
            assertThat(d.source()).isEqualTo(json("{'b':[1,2],'c':'x'}"));
            assertThat(d.message()).contains("{\"b\":[1,2],\"c\":\"x\"}");
        });
    }

    @Test
    void propertyNamesThatAreNotIdentifiersUseBracketNotation() {
        assertThat(compare("{'first name':'a'}", "{'first name':'b'}")).singleElement()
                .extracting(Difference::jsonPath).isEqualTo("$['first name']");
    }

    @Nested
    class OrderedArrays {

        @Test
        void reorderedArrayIsDifferentByDefault() {
            List<Difference> differences = compare("{'tags':['a','b']}", "{'tags':['b','a']}");

            assertThat(differences).extracting(Difference::jsonPath).containsExactly("$.tags[0]", "$.tags[1]");
            assertThat(differences).extracting(Difference::type).containsOnly(DifferenceType.VALUE_MISMATCH);
        }

        @Test
        void extraElementsAreMissingOnTheOtherSide() {
            assertThat(compare("[1,2,3]", "[1,2]")).singleElement().satisfies(d -> {
                assertThat(d.jsonPath()).isEqualTo("$[2]");
                assertThat(d.type()).isEqualTo(DifferenceType.MISSING_IN_TARGET);
            });
        }
    }

    @Nested
    class UnorderedArrays {

        @Test
        void globalUnorderedModeIgnoresElementOrder() {
            Comparison settings = new Comparison(null, null, null, ArrayOrder.UNORDERED, null);

            assertThat(compare(settings, "{'tags':['a','b','b']}", "{'tags':['b','a','b']}")).isEmpty();
        }

        @Test
        void unorderedArrayWithoutKeyReportsUnmatchedElements() {
            Comparison settings = new Comparison(null, null, null, null, List.of(new UnorderedArray("$.tags", null)));

            List<Difference> differences = compare(settings, "{'tags':['a','b','c']}", "{'tags':['c','x','a']}");

            assertThat(differences).extracting(Difference::jsonPath, Difference::type).containsExactly(
                    tuple("$.tags[1]", DifferenceType.MISSING_IN_TARGET),
                    tuple("$.tags[1]", DifferenceType.MISSING_IN_SOURCE));
            assertThat(differences.get(0).source().asText()).isEqualTo("b");
            assertThat(differences.get(1).target().asText()).isEqualTo("x");
        }

        @Test
        void unorderedRuleOnlyAppliesToItsPath() {
            Comparison settings = new Comparison(null, null, null, null, List.of(new UnorderedArray("$.tags", null)));

            assertThat(compare(settings, "{'tags':['a','b'],'other':[1,2]}", "{'tags':['b','a'],'other':[2,1]}"))
                    .extracting(Difference::jsonPath).containsExactly("$.other[0]", "$.other[1]");
        }

        @Test
        void keyedArrayPairsElementsByKeyAndComparesFields() {
            Comparison settings = new Comparison(null, null, null, null,
                    List.of(new UnorderedArray("$.items", "sku")));

            List<Difference> differences = compare(settings,
                    "{'items':[{'sku':'A','qty':1},{'sku':'B','qty':2},{'sku':'C','qty':3}]}",
                    "{'items':[{'sku':'B','qty':5},{'sku':'A','qty':1},{'sku':'D','qty':3}]}");

            assertThat(differences).extracting(Difference::jsonPath, Difference::type).containsExactly(
                    tuple("$.items[1].qty", DifferenceType.VALUE_MISMATCH),
                    tuple("$.items[2]", DifferenceType.MISSING_IN_TARGET),
                    tuple("$.items[2]", DifferenceType.MISSING_IN_SOURCE));
        }

        @Test
        void keyMatchingIsTypeAware() {
            Comparison settings = new Comparison(null, null, null, null, List.of(new UnorderedArray("$[*].lines", "id")));

            List<Difference> differences = compare(settings, "[{'lines':[{'id':1},{'id':2}]}]",
                    "[{'lines':[{'id':2.0},{'id':'1'}]}]");

            assertThat(differences).extracting(Difference::jsonPath, Difference::type).containsExactly(
                    tuple("$[0].lines[0]", DifferenceType.MISSING_IN_TARGET),
                    tuple("$[0].lines[1]", DifferenceType.MISSING_IN_SOURCE));
        }
    }

    @Nested
    class IgnoredPaths {

        @Test
        void ignoredPathsAndTheirChildrenAreSkipped() {
            Comparison settings = new Comparison(null, null, List.of("$.generatedAt", "$.meta"), null, null);

            assertThat(compare(settings, "{'id':1,'generatedAt':'2026-01-01','meta':{'trace':'a'}}",
                    "{'id':1,'generatedAt':'2026-11-18','meta':{'trace':'b','extra':1}}")).isEmpty();
        }

        @Test
        void wildcardsMatchAnyIndexOrProperty() {
            Comparison settings = new Comparison(null, null, List.of("$.items[*].id", "$.links.*.href"), null, null);

            assertThat(compare(settings,
                    "{'items':[{'id':'x1','sku':'A'},{'id':'x2','sku':'B'}],'links':{'self':{'href':'a','rel':'s'}}}",
                    "{'items':[{'id':'y1','sku':'A'},{'sku':'B'}],'links':{'self':{'href':'b','rel':'s'}}}"))
                    .isEmpty();
        }

        @Test
        void ignoredPathsApplyInsideUnorderedArrays() {
            Comparison settings = new Comparison(null, null, List.of("$.items[*].id"), ArrayOrder.UNORDERED, null);

            assertThat(compare(settings, "{'items':[{'id':1,'sku':'A'},{'id':2,'sku':'B'}]}",
                    "{'items':[{'id':9,'sku':'B'},{'id':8,'sku':'A'}]}")).isEmpty();
        }

        @Test
        void otherDifferencesAreStillReported() {
            Comparison settings = new Comparison(null, null, List.of("$.generatedAt"), null, null);

            assertThat(compare(settings, "{'generatedAt':'a','total':1}", "{'generatedAt':'b','total':2}"))
                    .extracting(Difference::jsonPath).containsExactly("$.total");
        }

        @Test
        void invalidPathIsRejected() {
            assertThatThrownBy(() -> new JsonComparator(new Comparison(null, null, List.of("items.id"), null, null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("items.id");
        }
    }

    @Nested
    class TypeMatchingModes {

        private final JsonComparator comparator = new JsonComparator(new Comparison(null, null, null, null, null));

        private List<Difference> compare(String source, String target, TypeMatching mode) {
            return comparator.compare(json(source), json(target), mode);
        }

        @Test
        void strictIsTheDefault() {
            assertThat(comparator.defaultTypeMatching()).isEqualTo(TypeMatching.STRICT);
            assertThat(comparator.compare(json("{'n':'11'}"), json("{'n':11}"))).singleElement()
                    .extracting(Difference::type).isEqualTo(DifferenceType.TYPE_MISMATCH);
        }

        @Test
        void lenientConvertsStringsToNumbers() {
            assertThat(compare("{'a':'11','b':'1.50','c':-2}", "{'a':11,'b':1.5,'c':'-2'}", TypeMatching.LENIENT))
                    .isEmpty();
        }

        @Test
        void lenientConvertsStringsToBooleans() {
            assertThat(compare("{'a':'true','b':false}", "{'a':true,'b':'false'}", TypeMatching.LENIENT)).isEmpty();
        }

        @Test
        void lenientReportsDifferentConvertedValuesAsValueMismatchWithOriginalValues() {
            assertThat(compare("{'n':'12'}", "{'n':11}", TypeMatching.LENIENT)).singleElement().satisfies(d -> {
                assertThat(d.type()).isEqualTo(DifferenceType.VALUE_MISMATCH);
                assertThat(d.source().isTextual()).isTrue();
                assertThat(d.target().isNumber()).isTrue();
                assertThat(d.message()).isEqualTo("$.n: source=\"12\", target=11");
            });
        }

        @Test
        void lenientKeepsTypeMismatchWhenNotConvertible() {
            List<Difference> differences = compare(
                    "{'a':'abc','b':' 11','c':null,'d':1,'e':{},'f':'yes'}",
                    "{'a':11,'b':11,'c':'null','d':true,'e':[],'f':true}", TypeMatching.LENIENT);

            assertThat(differences).extracting(Difference::jsonPath).containsExactly("$.a", "$.b", "$.c", "$.d",
                    "$.e", "$.f");
            assertThat(differences).extracting(Difference::type).containsOnly(DifferenceType.TYPE_MISMATCH);
        }

        @Test
        void lenientStillDistinguishesNullFromMissing() {
            assertThat(compare("{'a':null}", "{}", TypeMatching.LENIENT)).singleElement()
                    .extracting(Difference::type).isEqualTo(DifferenceType.MISSING_IN_TARGET);
        }

        @Test
        void lenientAppliesToKeyMatchingOfUnorderedArrays() {
            JsonComparator keyed = new JsonComparator(
                    new Comparison(null, null, null, null, List.of(new UnorderedArray("$.items", "id"))));
            String source = "{'items':[{'id':1,'qty':'2'},{'id':2,'qty':1}]}";
            String target = "{'items':[{'id':'2','qty':1},{'id':'1','qty':2}]}";

            assertThat(keyed.compare(json(source), json(target), TypeMatching.LENIENT)).isEmpty();
            assertThat(keyed.compare(json(source), json(target), TypeMatching.STRICT)).isNotEmpty();
        }

        @Test
        void configuredDefaultIsUsedWhenNoModeIsGiven() {
            JsonComparator lenientByDefault = new JsonComparator(
                    new Comparison(TypeMatching.LENIENT, null, null, null, null));

            assertThat(lenientByDefault.compare(json("['11']"), json("[11]"))).isEmpty();
            assertThat(lenientByDefault.compare(json("['11']"), json("[11]"), TypeMatching.STRICT)).isNotEmpty();
        }
    }

    @Nested
    class NullMatchingModes {

        private final JsonComparator comparator = new JsonComparator(new Comparison(null, null, null, null, null));

        private List<Difference> compare(String source, String target, NullMatching mode) {
            return comparator.compare(json(source), json(target), new CompareOptions(null, mode));
        }

        @Test
        void strictIsTheDefaultAndReportsNullVersusMissing() {
            assertThat(comparator.defaults().nullMatching()).isEqualTo(NullMatching.STRICT);
            assertThat(compare("{'a':null,'b':1}", "{'b':1,'c':null}", NullMatching.STRICT))
                    .extracting(Difference::jsonPath, Difference::type).containsExactly(
                            tuple("$.a", DifferenceType.MISSING_IN_TARGET),
                            tuple("$.c", DifferenceType.MISSING_IN_SOURCE));
        }

        @Test
        void lenientIgnoresNullPropertiesMissingOnTheOtherSide() {
            assertThat(compare("{'a':null,'b':1,'nested':{'x':null}}", "{'b':1,'c':null,'nested':{}}",
                    NullMatching.LENIENT)).isEmpty();
        }

        @Test
        void lenientStillReportsMissingNonNullValues() {
            assertThat(compare("{'a':null,'b':0,'c':''}", "{}", NullMatching.LENIENT))
                    .extracting(Difference::jsonPath).containsExactly("$.b", "$.c");
        }

        @Test
        void lenientStillReportsNullVersusAValue() {
            assertThat(compare("{'a':null}", "{'a':1}", NullMatching.LENIENT)).singleElement()
                    .extracting(Difference::type).isEqualTo(DifferenceType.TYPE_MISMATCH);
        }

        @Test
        void lenientDoesNotApplyToArrayElements() {
            assertThat(compare("{'list':[1,null]}", "{'list':[1]}", NullMatching.LENIENT)).singleElement()
                    .extracting(Difference::jsonPath).isEqualTo("$.list[1]");
        }

        @Test
        void lenientAppliesInsideUnorderedArrays() {
            JsonComparator unordered = new JsonComparator(
                    new Comparison(null, null, null, ArrayOrder.UNORDERED, null));

            assertThat(unordered.compare(json("[{'id':1,'note':null},{'id':2}]"), json("[{'id':2,'note':null},{'id':1}]"),
                    new CompareOptions(null, NullMatching.LENIENT))).isEmpty();
        }

        @Test
        void configuredDefaultIsUsedAndCombinesWithTypeMatching() {
            JsonComparator lenientByDefault = new JsonComparator(
                    new Comparison(TypeMatching.LENIENT, NullMatching.LENIENT, null, null, null));

            assertThat(lenientByDefault.compare(json("{'a':null,'n':'1'}"), json("{'n':1}"))).isEmpty();
            assertThat(lenientByDefault.compare(json("{'a':null,'n':'1'}"), json("{'n':1}"),
                    new CompareOptions(TypeMatching.STRICT, NullMatching.STRICT))).hasSize(2);
        }
    }

    @Test
    void rootLevelDifferences() {
        assertThat(compare("[1]", "{'a':1}")).singleElement().satisfies(d -> {
            assertThat(d.jsonPath()).isEqualTo("$");
            assertThat(d.type()).isEqualTo(DifferenceType.TYPE_MISMATCH);
        });
        assertThat(new JsonComparator(new Comparison(null, null, null, null, null)).compare(null, json("{'a':1}")))
                .singleElement().extracting(Difference::type).isEqualTo(DifferenceType.MISSING_IN_SOURCE);
    }
}
