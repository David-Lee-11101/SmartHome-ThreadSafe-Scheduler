package concurrency.demo;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleSpecTest {
    @Test
    void fixtureModelIsImmutable() {
        RuleSpec rule = new RuleSpec("r1", "demo",
                Arrays.asList(new RuleSpec.DeviceState("sensor", "on")),
                Collections.emptyList(),
                Arrays.asList(new RuleSpec.DeviceState("lamp", "on")));
        assertThrows(UnsupportedOperationException.class,
                () -> rule.getActions().add(new RuleSpec.DeviceState("other", "off")));
        assertEquals("r1", rule.getId());
    }

    @Test
    void enforcesSharedV0RequiredContent() {
        RuleSpec.DeviceState validState = new RuleSpec.DeviceState("sensor", "on");
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSpec("r1", "", Arrays.asList(validState),
                        Collections.emptyList(), Arrays.asList(validState)));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSpec("r1", "demo", Collections.emptyList(),
                        Collections.emptyList(), Arrays.asList(validState)));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSpec("r1", "demo", Arrays.asList(validState),
                        Collections.emptyList(), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleSpec("r1", "demo", Arrays.asList(validState, null),
                        Collections.emptyList(), Arrays.asList(validState)));
        assertThrows(IllegalArgumentException.class, () -> new RuleSpec.DeviceState("sensor", ""));
    }

    @Test
    void jsonFixtureParserRejectsSchemaShapeViolations() {
        String valid = "{\"id\":\"r1\",\"description\":\"demo\","
                + "\"triggers\":[{\"device\":\"sensor\",\"state\":\"on\"}],"
                + "\"conditions\":[],\"actions\":[{\"device\":\"lamp\",\"state\":\"on\"}]}";
        assertEquals(1, SmartRuleSimulationDemo.parseRules(new StringReader("[" + valid + "]")).size());
        assertThrows(IllegalArgumentException.class,
                () -> SmartRuleSimulationDemo.parseRules(new StringReader("[]")));
        assertThrows(IllegalArgumentException.class,
                () -> SmartRuleSimulationDemo.parseRules(new StringReader(
                        "[" + valid.substring(0, valid.length() - 1) + ",\"extra\":true}]")));
        assertThrows(IllegalArgumentException.class,
                () -> SmartRuleSimulationDemo.parseRules(new StringReader(
                        "[" + valid.replace("\"actions\":[{\"device\":\"lamp\",\"state\":\"on\"}]",
                                "\"actions\":[]") + "]")));
    }
}
