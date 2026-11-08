package io.github.gringoestrangeiro.openradplugin.config;

import io.github.gringoestrangeiro.openradplugin.crypto.Identity;
import io.github.gringoestrangeiro.openradplugin.session.CoordinatorEndpoint;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * Edits {@code config.yml} and {@code secrets.properties} for the in-game setup commands. Every
 * edit validates the replacement before publishing it. Each file is
 * replaced atomically; password changes publish a fresh secret before switching the YAML
 * reference, so a failed YAML write preserves the previously referenced password.
 */
public final class ConfigEditor {
    private static final int MAX_FILE = 256 * 1024;
    private static final System.Logger LOG = System.getLogger(ConfigEditor.class.getName());

    private final Path directory;
    private final boolean onlineMode;

    public ConfigEditor(Path directory) {
        this(directory, true);
    }

    public ConfigEditor(Path directory, boolean onlineMode) {
        this.directory = directory;
        this.onlineMode = onlineMode;
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
        for (Map<String, Object> n : networks) if (name.equals(Objects.toString(n.get("name"), ""))) entry = n;
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
        String previousSecret = secret;
        if (password != null) {
            secret = freshSecretKey(name, networks, storedSecretKeys());
            entry.put("secret", secret);
        }
        yaml.set("vpn.networks", networks);
        yaml.set("bridge.enabled", true);
        VpnSettings settings = validate(yaml, password == null ? "" : secret);
        if (password != null) writeSecret(secret, password);
        try {
            save(yaml);
        } catch (IOException failure) {
            if (password != null) removeUnusedSecretBestEffort(secret);
            throw failure;
        }
        if (password != null
                && !previousSecret.isEmpty()
                && settings.networks().stream().noneMatch(n -> previousSecret.equals(n.secret()))) {
            removeUnusedSecretBestEffort(previousSecret);
        }
        return new JoinResult(added, secret);
    }

    /** Removes a configured network and its stored password. Returns false if it was absent. */
    public synchronized boolean leave(String name) throws IOException {
        YamlConfiguration yaml = load();
        List<Map<String, Object>> networks = networks(yaml);
        Map<String, Object> entry = null;
        for (Map<String, Object> n : networks) if (name.equals(Objects.toString(n.get("name"), ""))) entry = n;
        if (entry == null) return false;
        networks.remove(entry);
        String secret = Objects.toString(entry.get("secret"), "");
        yaml.set("vpn.networks", networks);
        if (networks.isEmpty() && !MinecraftNetworkSettings.from(yaml).enabled()) {
            yaml.set("bridge.enabled", false);
        }
        VpnSettings settings = validate(yaml, "");
        save(yaml);
        boolean stillUsed = settings.networks().stream().anyMatch(n -> n.secret().equals(secret));
        if (!secret.isEmpty() && !stillUsed) removeUnusedSecretBestEffort(secret);
        return true;
    }

    /** Sets {@code bridge.enabled}; enabling still requires a valid network configuration. */
    public synchronized void enabled(boolean enabled) throws IOException {
        YamlConfiguration yaml = load();
        yaml.set("bridge.enabled", enabled);
        validate(yaml, "");
        save(yaml);
    }

    /** Replaces {@code vpn.minecraft-networks.select}; an empty list only lists the catalog. */
    public synchronized void publicSelection(List<String> selectors) throws IOException {
        YamlConfiguration yaml = load();
        yaml.set("vpn.minecraft-networks.enabled", true);
        yaml.set("vpn.minecraft-networks.select", new ArrayList<>(selectors));
        if (!selectors.isEmpty()) yaml.set("bridge.enabled", true);
        validate(yaml, "");
        save(yaml);
    }

    /** Sets {@code vpn.transports.force-relay}; enabling it requires the relay transport. */
    public synchronized void forceRelay(boolean enabled) throws IOException {
        YamlConfiguration yaml = load();
        yaml.set("vpn.transports.force-relay", enabled);
        validate(yaml, "");
        save(yaml);
    }

    private VpnSettings validate(YamlConfiguration yaml, String stagedSecret) throws IOException {
        VpnSettings settings = VpnSettings.from(yaml, onlineMode);
        if (settings.bridge().enabled()) {
            CoordinatorEndpoint.load(directory, settings.bootstrap(), settings.controlPort());
        }
        try (NetworkSecrets secrets = NetworkSecrets.readExisting(directory)) {
            secrets.validate(settings, stagedSecret);
        }
        return settings;
    }

    static String freshSecretKey(
            String name, List<Map<String, Object>> networks, Set<String> storedKeys) {
        String slug =
                name.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_.-]+", "-")
                        .replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        if (slug.isEmpty()) slug = Integer.toHexString(name.hashCode());
        Set<String> used = new HashSet<>(storedKeys);
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
        if (Files.isSymbolicLink(path) || Files.size(path) > NetworkSecrets.MAX_FILE_BYTES)
            throw new IOException("invalid secrets file");
        return new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    private void writeSecret(String key, char[] password) throws IOException {
        List<String> lines = secretLines();
        // The key is fresh. Do not edit physical lines of existing Properties
        // records: a line resembling a key may be part of a continued value.
        // A blank line also terminates an EOF continuation before the new key.
        lines.add("");
        lines.add(key + "=" + escape(password));
        replace(secretsPath(), String.join("\n", lines) + "\n", true);
    }

    private Set<String> storedSecretKeys() throws IOException {
        Properties properties = new Properties();
        properties.load(new java.io.StringReader(String.join("\n", secretLines())));
        return properties.stringPropertyNames();
    }

    private void removeUnusedSecretBestEffort(String key) {
        try {
            removeSecret(key);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not remove an unused secret from secrets.properties ("
                            + e.getClass().getSimpleName() + ")");
        }
    }

    private void removeSecret(String key) throws IOException {
        List<String> lines = secretLines();
        List<String> retained = new ArrayList<>();
        boolean removed = false;
        for (int at = 0; at < lines.size(); ) {
            if (!startsProperty(lines.get(at))) {
                retained.add(lines.get(at++));
                continue;
            }
            int start = at;
            StringBuilder record = new StringBuilder();
            boolean continued;
            do {
                String line = lines.get(at++);
                record.append(line).append('\n');
                int slashes = 0;
                for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) slashes++;
                continued = (slashes & 1) != 0;
            } while (continued && at < lines.size());
            Properties properties = new Properties();
            properties.load(new java.io.StringReader(record.toString()));
            if (properties.size() == 1 && properties.containsKey(key)) removed = true;
            else retained.addAll(lines.subList(start, at));
        }
        if (removed) replace(secretsPath(), String.join("\n", retained) + "\n", true);
    }

    private static boolean startsProperty(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t' && c != '\f') return c != '#' && c != '!';
        }
        return false;
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
        int maxBytes = secret ? NetworkSecrets.MAX_FILE_BYTES : MAX_FILE;
        if (content.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IOException(path.getFileName() + " exceeds size limit");
        }
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
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("configuration edit cancelled");
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
