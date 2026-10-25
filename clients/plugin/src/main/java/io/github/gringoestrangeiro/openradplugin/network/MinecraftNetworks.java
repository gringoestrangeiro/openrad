package io.github.gringoestrangeiro.openradplugin.network;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Selectors are derived from the authenticated public catalog, never guessed network names. */
public final class MinecraftNetworks {
    private MinecraftNetworks() {}

    private static final Pattern GROUP = Pattern.compile("(?iu)^Minecraft \\[([^\\]]+)]$");
    private static final Pattern NUMBER = Pattern.compile("^(.*) ([1-9][0-9]{0,5})$");
    private static final Map<String, String> LANGUAGES =
            Map.ofEntries(
                    Map.entry("portugues", "ptbr"), Map.entry("english", "english"),
                    Map.entry("tieng viet", "vietnamese"), Map.entry("русскии", "russian"),
                    Map.entry("中文", "chinese"), Map.entry("espanol", "spanish"),
                    Map.entry("francais", "french"), Map.entry("deutsch", "german"),
                    Map.entry("italiano", "italian"), Map.entry("turkce", "turkish"),
                    Map.entry("bahasa indonesia", "indonesian"), Map.entry("polski", "polish"),
                    Map.entry("hungarian", "hungarian"), Map.entry("srpski", "serbian"),
                    Map.entry("latviesu", "latvian"), Map.entry("norsk", "norwegian"),
                    Map.entry("lietuviu", "lithuanian"), Map.entry("dansk", "danish"),
                    Map.entry("eesti", "estonian"), Map.entry("svenska", "swedish"),
                    Map.entry("slovencina", "slovak"), Map.entry("slovenscina", "slovenian"),
                    Map.entry("cesky", "czech"), Map.entry("hrvatski", "croatian"),
                    Map.entry("nederlands", "dutch"), Map.entry("suomi", "finnish"),
                    Map.entry("romana", "romanian"), Map.entry("ελληνικα", "greek"),
                    Map.entry("български", "bulgarian"), Map.entry("украінська", "ukrainian"),
                    Map.entry("עברית", "hebrew"), Map.entry("العربية", "arabic"),
                    Map.entry("فارسی", "persian"), Map.entry("ภาษาไทย", "thai"),
                    Map.entry("日本語", "japanese"), Map.entry("한국어", "korean"));

    /** Available language selectors for the Fabric configuration UI before catalog fetch. */
    public static List<String> knownLanguageGroups() {
        return LANGUAGES.values().stream().distinct().sorted().toList();
    }

    public record Entry(
            String selector, String language, int number, String name, long reportedCount) {}

    public record Selection(List<Entry> networks, List<String> unmatched) {
        public Selection {
            networks = List.copyOf(networks);
            unmatched = List.copyOf(unmatched);
        }
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static String slug(String value) {
        return normalized(value).replaceAll("[^\\p{L}\\p{Nd}]+", "-").replaceAll("^-|-$", "");
    }

    public static List<Entry> catalog(Collection<PublicNetworks.Network> raw) {
        if (raw.size() > 1024) throw new IllegalArgumentException("Minecraft catalog limit");
        Map<String, Entry> names = new LinkedHashMap<>();
        for (var network : raw) {
            String name = network.name();
            if (name.isBlank()
                    || name.length() > 255
                    || name.chars().anyMatch(Character::isISOControl)) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.equals("minecraft")
                    && !lower.startsWith("minecraft ")
                    && !lower.startsWith("minecraft[")) continue;
            var group = GROUP.matcher(name);
            String language = "other", selector = slug(name);
            int number = 1;
            if (group.matches()) {
                String label = group.group(1);
                var numbered = NUMBER.matcher(label);
                if (numbered.matches()) {
                    label = numbered.group(1);
                    number = Integer.parseInt(numbered.group(2));
                }
                language = LANGUAGES.getOrDefault(normalized(label), slug(label));
                selector = language + "-" + number;
            }
            names.putIfAbsent(
                    name, new Entry(selector, language, number, name, network.reportedCount()));
        }
        return names.values().stream()
                .sorted(
                        Comparator.comparing(Entry::language)
                                .thenComparingInt(Entry::number)
                                .thenComparing(Entry::name))
                .toList();
    }

    public static String validateSelector(String value) {
        String s = value.toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty() || s.length() > 128 || !s.matches("[\\p{L}\\p{Nd}]+(?:-[\\p{L}\\p{Nd}]+)*"))
            throw new IllegalArgumentException("invalid Minecraft network selector");
        return s;
    }

    public static boolean matches(Entry entry, String selector) {
        String s = validateSelector(selector);
        return s.equals("all") || s.equals("all-" + entry.language()) || s.equals(entry.selector());
    }

    public static Selection select(List<Entry> catalog, List<String> selectors, int limit) {
        if (limit < 1 || limit > 1024)
            throw new IllegalArgumentException("Minecraft selection limit");
        Map<String, Entry> selected = new LinkedHashMap<>();
        List<String> unmatched = new ArrayList<>();
        for (String selector : selectors) {
            String s = validateSelector(selector);
            var matches = catalog.stream().filter(entry -> matches(entry, s)).toList();
            if (matches.isEmpty()) unmatched.add(s);
            for (Entry entry : matches) selected.putIfAbsent(entry.name(), entry);
        }
        if (selected.size() > limit)
            throw new IllegalArgumentException(
                    "Minecraft selection exceeds max-networks; raise the limit");
        return new Selection(List.copyOf(selected.values()), unmatched);
    }

    public static List<String> selectors(List<Entry> catalog) {
        Set<String> values = new TreeSet<>();
        values.add("all");
        for (Entry entry : catalog) {
            values.add("all-" + entry.language());
            values.add(entry.selector());
        }
        return List.copyOf(values);
    }

    public static String optionsYaml(List<Entry> catalog) {
        StringBuilder out =
                new StringBuilder(
                        "# Generated from the authenticated catalog.\n"
                            + "# Copy desired items into config.yml ->"
                            + " vpn.minecraft-networks.select\n"
                            + "# all = every network; all-<language> = every number of that"
                            + " language.\n"
                            + "options:\n");
        for (String selector : selectors(catalog))
            out.append("  - \"").append(selector).append("\"\n");
        return out.toString();
    }
}
