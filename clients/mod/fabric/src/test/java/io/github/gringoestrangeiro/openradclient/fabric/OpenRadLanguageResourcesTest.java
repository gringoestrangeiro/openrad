package io.github.gringoestrangeiro.openradclient.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Locale files load without executing Minecraft; Minecraft shows en_us for any missing key. */
class OpenRadLanguageResourcesTest {
    @Test void mixinCompatibilityMatchesTheDeclaredJavaRuntime() throws Exception {
        var loader = getClass().getClassLoader();
        try (var metadata = loader.getResourceAsStream("fabric.mod.json");
             var mixins = loader.getResourceAsStream("openrad-client.mixins.json")) {
            assertNotNull(metadata);
            assertNotNull(mixins);
            var mod = new JsonParser().parse(new InputStreamReader(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
            var config = new JsonParser().parse(new InputStreamReader(mixins, StandardCharsets.UTF_8)).getAsJsonObject();
            String java = mod.getAsJsonObject("depends").get("java").getAsString();
            assertTrue(java.startsWith(">="));
            assertEquals("JAVA_" + java.substring(2), config.get("compatibilityLevel").getAsString());
        }
    }

    private JsonObject load(String locale) throws Exception {
        String resource = "assets/openrad-client/lang/" + locale + ".json";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static Set<String> keys(JsonObject object) {
        Set<String> keys = new HashSet<>();
        for (var entry : object.entrySet()) keys.add(entry.getKey());
        return keys;
    }

    @Test void dedicatedJapaneseStringsCoverTheCompleteOpenRadMenu() throws Exception {
        JsonObject base = load("en_us");
        JsonObject japanese = load("ja_jp");
        assertEquals(keys(base), keys(japanese));
        assertEquals("接続", japanese.get("openrad.menu.connect").getAsString());
        assertEquals("切断", japanese.get("openrad.menu.disconnect").getAsString());
        assertEquals("保存", japanese.get("openrad.menu.save").getAsString());
    }

    @Test void localePlaceholdersMatchTheEnglishTemplate() throws Exception {
        JsonObject base = load("en_us");
        for (String language : Set.of("en_us", "pt_br", "ja_jp", "de_de", "fr_fr",
                "es_es", "it_it", "ko_kr", "pl_pl", "ru_ru", "tr_tr", "uk_ua",
                "ar_sa", "zh_cn")) {
            JsonObject localized = load(language);
            for (String key : keys(localized)) {
                if (!base.has(key)) continue;
                String expected = base.get(key).getAsString();
                String actual = localized.get(key).getAsString();
                assertEquals(expected.split("%s", -1).length, actual.split("%s", -1).length,
                        language + ": " + key);
                assertFalse(actual.isBlank(), language + ": " + key);
            }
        }
    }

    @Test void everyLocaleKeyExistsInEnglish() throws Exception {
        JsonObject base = load("en_us");
        for (String language : Set.of("pt_br", "ja_jp", "de_de", "fr_fr", "es_es", "it_it", "ko_kr",
                "pl_pl", "ru_ru", "tr_tr", "uk_ua", "ar_sa", "zh_cn"))
            for (String key : keys(load(language)))
                assertTrue(base.has(key), language + ": " + key);
    }
}
