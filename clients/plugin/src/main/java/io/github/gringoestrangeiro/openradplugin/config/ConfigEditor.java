package io.github.gringoestrangeiro.openradplugin.config;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * Edits {@code config.yml} and {@code secrets.properties} for the in-game setup commands. Every
 * edit is validated with {@link VpnSettings#from} before it replaces the file, and both files are
 * replaced atomically so a failed write never leaves a half-written configuration behind.
 */
public final class ConfigEditor {
    private static final int MAX_FILE = 256 * 1024;

    private final Path directory;

    public ConfigEditor(Path directory) {
        this.directory = directory;
    }

    /** Result of {@link #join}: whether the network was new and which secret key it uses. */
    public record JoinResult(boolean added, String secretKey) {}

    /**
     * Adds or updates a network entry, stores its password (if any) and enables the bridge. An
     * existing network keeps its ACLs; its password reference is replaced only when a password is
     * given.
     */
    public synchronized JoinResult join(String name, char[] password) throws IOException {
        require(name != null && !name.isBlank() && name.length() <= 255, "nome de rede invalido");
        if (password != null) validatePassword(password);
        YamlConfiguration yaml = load();
        List<Map<String, Object>> networks = networks(yaml);
        Map<String, Object> entry = null;
        for (Map<String, Object> n : networks) if (name.equals(n.get("name"))) entry = n;
        boolean added = entry == null;
        if (added) {
            require(networks.size() < 128, "limite de 128 redes atingido");
            entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("secret", "");
            entry.put("allow-rids", new ArrayList<>());
            entry.put("deny-rids", new ArrayList<>());
            networks.add(entry);
        }
        String secret = Objects.toString(entry.get("secret"), "");
        if (password != null) {
            if (secret.isEmpty()) secret = freshSecretKey(name, networks);
            entry.put("secret", secret);
        }
        yaml.set("vpn.networks", networks);
        yaml.set("bridge.enabled", true);
        VpnSettings.from(yaml);
        if (password != null) writeSecret(secret, password);
        save(yaml);
        return new JoinResult(added, secret);
    }

    /** Removes a configured network and its stored password. Returns false if it was absent. */
    public synchronized boolean leave(String name) throws IOException {
        YamlConfiguration yaml = load();
        List<Map<String, Object>> networks = networks(yaml);
        Map<String, Object> entry = null;
        for (Map<String, Object> n : networks) if (name.equals(n.get("name"))) entry = n;
        if (entry == null) return false;
        networks.remove(entry);
        String secret = Objects.toString(entry.get("secret"), "");
        yaml.set("vpn.networks", networks);
        VpnSettings settings;
        try {
            settings = VpnSettings.from(yaml);
        } catch (IllegalArgumentException e) {
            // The last private network was removed and no public discovery is active.
            yaml.set("bridge.enabled", false);
            settings = VpnSettings.from(yaml);
        }
        save(yaml);
        boolean stillUsed = settings.networks().stream().anyMatch(n -> n.secret().equals(secret));
        if (!secret.isEmpty() && !stillUsed) removeSecret(secret);
        return true;
    }

    /** Sets {@code bridge.enabled}; enabling still requires a valid network configuration. */
    public synchronized void enabled(boolean enabled) throws IOException {
        YamlConfiguration yaml = load();
        yaml.set("bridge.enabled", enabled);
        VpnSettings.from(yaml);
        save(yaml);
    }

    /** Replaces {@code vpn.minecraft-networks.select}; an empty list only lists the catalog. */
    public synchronized void publicSelection(List<String> selectors) throws IOException {
        YamlConfiguration yaml = load();
        yaml.set("vpn.minecraft-networks.enabled", true);
        yaml.set("vpn.minecraft-networks.select", new ArrayList<>(selectors));
        if (!selectors.isEmpty()) yaml.set("bridge.enabled", true);
        VpnSettings.from(yaml);
        save(yaml);
    }

    static String freshSecretKey(String name, List<Map<String, Object>> networks) {
        String slug =
                name.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_.-]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        if (slug.isEmpty()) slug = Integer.toHexString(name.hashCode());
        Set<String> used = new HashSet<>();
        for (Map<String, Object> n : networks) used.add(Objects.toString(n.get("secret"), ""));
        String key = "rede-" + slug;
        for (int i = 2; used.contains(key); i++) key = "rede-" + slug + "-" + i;
        return key;
    }

    private static void validatePassword(char[] password) {
        require(
                password.length >= 6 && password.length <= 256,
                "a senha da rede deve ter de 6 a 256 caracteres");
        for (char c : password)
            require(!Character.isISOControl(c), "a senha contem caracteres de controle");
    }

    private Path configPath() {
        return directory.resolve("config.yml");
    }

    private YamlConfiguration load() throws IOException {
        Path path = configPath();
        if (Files.isSymbolicLink(path) || Files.size(path) > MAX_FILE)
            throw new IOException("configuration path/size invalid");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("config.yml nao e um YAML valido; corrija o arquivo");
        }
        return yaml;
    }

    private static List<Map<String, Object>> networks(YamlConfiguration yaml) {
        List<Map<String, Object>> networks = new ArrayList<>();
        for (Map<?, ?> m : yaml.getMapList("vpn.networks")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            networks.add(copy);
        }
        return networks;
    }

    private void save(YamlConfiguration yaml) throws IOException {
        replace(configPath(), yaml.saveToString(), false);
    }

    private Path secretsPath() {
        return directory.resolve("secrets.properties");
    }

    private List<String> secretLines() throws IOException {
        Path path = secretsPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        if (Files.isSymbolicLink(path) || Files.size(path) > 65536)
            throw new IOException("invalid secrets file");
        return new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    private void writeSecret(String key, char[] password) throws IOException {
        List<String> lines = secretLines();
        lines.removeIf(line -> definesKey(line, key));
        lines.add(key + "=" + escape(password));
        replace(secretsPath(), String.join("\n", lines) + "\n", true);
    }

    private void removeSecret(String key) throws IOException {
        List<String> lines = secretLines();
        if (lines.removeIf(line -> definesKey(line, key)))
            replace(secretsPath(), String.join("\n", lines) + "\n", true);
    }

    private static boolean definesKey(String line, String key) {
        String trimmed = line.stripLeading();
        if (!trimmed.startsWith(key)) return false;
        String rest = trimmed.substring(key.length()).stripLeading();
        return rest.isEmpty() || rest.charAt(0) == '=' || rest.charAt(0) == ':';
    }

    /** java.util.Properties escaping for a value: backslash, leading space, and separators. */
    static String escape(char[] value) {
        StringBuilder out = new StringBuilder(value.length + 8);
        for (int i = 0; i < value.length; i++) {
            char c = value[i];
            if (c == '\\') out.append("\\\\");
            else if (c == ' ' && i == 0) out.append("\\ ");
            else if (c == '#' || c == '!' || c == '=' || c == ':') out.append('\\').append(c);
            else out.append(c);
        }
        return out.toString();
    }

    private static void replace(Path path, String content, boolean secret) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            if (secret) {
                try {
                    Files.createFile(
                            temp,
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(temp);
                }
                Identity.secureFile(temp);
            }
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (secret) Identity.secureFile(path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
