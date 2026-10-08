package com.deruy.plugin;
import com.deruy.plugin.config.ConfigValidator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {
    @org.junit.jupiter.api.BeforeEach void setupServer() { org.mockbukkit.mockbukkit.MockBukkit.mock(); }
    @org.junit.jupiter.api.AfterEach void teardownServer() { org.mockbukkit.mockbukkit.MockBukkit.unmock(); }

    private YamlConfiguration config() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"),StandardCharsets.UTF_8));
    }
    @Test void shippedConfigurationIsValid() { assertDoesNotThrow(() -> ConfigValidator.validate(config())); }
    @Test void allLogoutModesAreAccepted() {
        for (String mode : new String[]{"CHAT","KILL","NONE","kill"}) {
            var c=config(); c.set("combat-tag.logout-penalty",mode); assertDoesNotThrow(()->ConfigValidator.validate(c));
        }
    }
    @Test void unknownLogoutModeIsRejected() { var c=config(); c.set("combat-tag.logout-penalty","death"); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
    @Test void nonFinitePitchIsRejected() { var c=config(); c.set("voice-effects.pitch-semitones",Double.NaN); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
    @Test void zeroCaptureTimeIsRejected() { var c=config(); c.set("koth.required-seconds",0); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
    @Test void invertedDropCountIsRejected() { var c=config(); c.set("supplydrop.normal.count-max",1); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
    @Test void unsafeBingoSizeIsRejected() { var c=config(); c.set("bingo.size",7); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
    @Test void roleCapBelowBanFloorIsRejected() { var c=config(); c.set("roles.l.max-hearts",5); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
    @Test void badRewardProbabilityIsRejected() { var c=config(); c.set("koth.reward-items",java.util.List.of("HEART:1.1")); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
    @Test void invalidRecipeSymbolIsRejected() { var c=config(); c.set("lifesteal.recipes.heart.shape",java.util.List.of("ZZZ","ZZZ","ZZZ")); assertThrows(IllegalArgumentException.class,()->ConfigValidator.validate(c)); }
}
