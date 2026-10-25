package io.github.gringoestrangeiro.openradclient.fabric;

import java.util.Locale;
import java.util.Map;

/** Display-only; never translates network names transmitted to the OpenRad service. */
final class OpenRadNetworkLabels {
    private static final Map<String, String> CODES = Map.ofEntries(
        Map.entry("ptbr", "pt-BR"), Map.entry("english", "en"), Map.entry("spanish", "es"),
        Map.entry("russian", "ru"), Map.entry("chinese", "zh-CN"), Map.entry("vietnamese", "vi"),
        Map.entry("german", "de"), Map.entry("french", "fr"), Map.entry("italian", "it"),
        Map.entry("turkish", "tr"), Map.entry("indonesian", "id"), Map.entry("polish", "pl"),
        Map.entry("hungarian", "hu"), Map.entry("serbian", "sr"), Map.entry("latvian", "lv"),
        Map.entry("norwegian", "no"), Map.entry("lithuanian", "lt"), Map.entry("danish", "da"),
        Map.entry("estonian", "et"), Map.entry("swedish", "sv"), Map.entry("slovak", "sk"),
        Map.entry("slovenian", "sl"), Map.entry("czech", "cs"), Map.entry("croatian", "hr"),
        Map.entry("dutch", "nl"), Map.entry("romanian", "ro"), Map.entry("greek", "el"),
        Map.entry("bulgarian", "bg"), Map.entry("ukrainian", "uk"), Map.entry("hebrew", "he"),
        Map.entry("arabic", "ar"), Map.entry("persian", "fa"), Map.entry("thai", "th"),
        Map.entry("japanese", "ja"), Map.entry("korean", "ko"));
    private OpenRadNetworkLabels() {}

    static String name(String group, String minecraftLanguage) {
        String code = CODES.get(group);
        if (code == null) return group;
        Locale locale = Locale.forLanguageTag(minecraftLanguage.replace('_','-'));
        String result = Locale.forLanguageTag(code).getDisplayLanguage(locale);
        if (result.isEmpty()) return group;
        // Portuguese Brazilian is distinguished from Portugal.
        if (group.equals("ptbr")) {
            String country = Locale.forLanguageTag(code).getDisplayCountry(locale);
            if (!country.isEmpty()) result = result + " (" + country + ")";
        }
        return result;
    }
}
