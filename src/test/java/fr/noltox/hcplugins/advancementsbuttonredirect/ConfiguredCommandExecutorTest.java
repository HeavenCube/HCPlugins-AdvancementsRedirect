package fr.noltox.hcplugins.advancementsbuttonredirect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguredCommandExecutorTest {

    @Test
    void absentOrBlankCommandRemainsDisabled() {
        assertEquals("", ConfiguredCommandExecutor.validateCommand(null));
        assertEquals("", ConfiguredCommandExecutor.validateCommand(" \t\n"));
    }

    @Test
    void preservesPlayerPlaceholderAndCommandArguments() {
        assertEquals("menu open profil {player}",
                ConfiguredCommandExecutor.validateCommand("  menu open profil {player}  "));
    }

    @Test
    void rejectsWrongYamlTypesInsteadOfExecutingTheirStringRepresentation() {
        for (Object invalid : List.of(123, true, List.of("menu"), Map.of("menu", "profil"))) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> ConfiguredCommandExecutor.validateCommand(invalid));
            assertTrue(failure.getMessage().contains("console-command"));
        }
    }
}
