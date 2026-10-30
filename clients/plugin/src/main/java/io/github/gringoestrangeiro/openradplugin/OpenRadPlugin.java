package io.github.gringoestrangeiro.openradplugin;

import io.github.gringoestrangeiro.openradplugin.config.*;
import io.github.gringoestrangeiro.openradplugin.diagnostics.HostDiagnostics;
import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;
import io.github.gringoestrangeiro.openradplugin.runtime.GenerationGate;
import io.github.gringoestrangeiro.openradplugin.runtime.VpnRuntime;

import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class OpenRadPlugin extends JavaPlugin {
    private volatile VpnSettings settings;
    private final GenerationGate<VpnRuntime> generations = new GenerationGate<>();
    private final Object applyLock = new Object();
    private ThreadPoolExecutor admin;
    private static final List<String> SUBCOMMANDS =
            List.of(
                    "help",
                    "status",
                    "join",
                    "leave",
                    "public",
                    "enable",
                    "disable",
                    "networks",
                    "minecraft",
                    "peers",
                    "reconnect",
                    "reload",
                    "diagnose");

    @Override
    public void onEnable() {
        generations.reset();
        if (getServer().getPluginManager().getPlugin("OpenRadBridge") != null) {
            getLogger().severe("O JAR antigo OpenRadBridge tambem esta instalado; remova-o "
                    + "antes de iniciar OpenRadPlugin. Nao execute os dois juntos.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            migrateLegacyData();
        } catch (IOException e) {
            getLogger().severe("Nao foi possivel migrar a configuracao antiga; VPN nao iniciada.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        admin =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(2),
                        io.github.gringoestrangeiro.openradplugin.util.ThreadCompat.platformFactory("OpenRad Plugin-admin-"),
                        new ThreadPoolExecutor.AbortPolicy());
        Objects.requireNonNull(getCommand("orb")).setExecutor(this::command);
        Objects.requireNonNull(getCommand("orb"))
                .setTabCompleter(
                        (sender, command, alias, args) -> {
                            if (!sender.hasPermission("openradplugin.admin")) return List.of();
                            VpnRuntime current = generations.current();
                            List<String> values =
                                    args.length == 1
                                            ? SUBCOMMANDS
                                            : args.length == 2
                                                            && args[0].equalsIgnoreCase("minecraft")
                                                            && current != null
                                                    ? java.util.stream.Stream.concat(
                                                                    java.util.stream.Stream.of(
                                                                            "options"),
                                                                    MinecraftNetworks.selectors(
                                                                            current.minecraftCatalog()
                                                                                    .entries())
                                                                            .stream())
                                                            .toList()
                                                    : args.length == 2
                                                                    && args[0].equalsIgnoreCase(
                                                                            "leave")
                                                            ? configuredNetworkNames()
                                                            : args.length == 2
                                                                            && args[0]
                                                                                    .equalsIgnoreCase(
                                                                                            "public")
                                                                    ? publicSuggestions(current)
                                                                    : List.<String>of();
                            String prefix =
                                    args.length == 0
                                            ? ""
                                            : args[args.length - 1].toLowerCase(Locale.ROOT);
                            return values.stream().filter(v -> v.startsWith(prefix)).toList();
                        });
        admin.execute(
                () -> {
                    try {
                        apply(readSettings());
                    } catch (Exception e) {
                        getLogger().severe("Configuration rejected; VPN offline: " + safeReason(e));
                    }
                });
        getLogger()
                .info(
                        "OpenRad Plugin "
                                + getPluginMeta().getVersion()
                                + ". Use /orb help para configurar sem editar arquivos.");
    }

    /**
     * Rename the old data directory once. Never auto-register a fresh device while a
     * valid legacy identity might still exist in the old directory.
     */
    private void migrateLegacyData() throws IOException {
        Path destination = getDataFolder().toPath();
        Path legacy = destination.resolveSibling("OpenRadBridge");
        if (Files.isSymbolicLink(legacy) || !Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS))
            return;
        if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(legacy, destination);
            getLogger().info("Configuracao e identidade existentes migradas para OpenRad Plugin.");
        } else if (!Files.exists(destination.resolve("identity.json"), LinkOption.NOFOLLOW_LINKS)
                && Files.exists(legacy.resolve("identity.json"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("legacy identity exists; refusing to start with a new profile");
        }
    }

    private VpnSettings readSettings() throws Exception {
        Path path = getDataFolder().toPath().resolve("config.yml");
        if (Files.isSymbolicLink(path) || Files.size(path) > 256 * 1024)
            throw new IOException("configuration path/size invalid");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            // Name the position only: the offending line could contain a pasted password.
            String where =
                    e.getCause() instanceof org.yaml.snakeyaml.error.MarkedYAMLException marked
                                    && marked.getProblemMark() != null
                            ? " na linha "
                                    + (marked.getProblemMark().getLine() + 1)
                                    + ", coluna "
                                    + (marked.getProblemMark().getColumn() + 1)
                            : "";
            throw new IllegalArgumentException(
                    "config.yml nao e um YAML valido" + where + "; confira a indentacao e as aspas");
        }
        VpnSettings parsed = VpnSettings.from(yaml);
        if (parsed.bridge().enabled()
                && parsed.networks().isEmpty()
                && parsed.minecraftNetworks().select().isEmpty())
            getLogger()
                    .warning(
                            "VPN ativada sem redes: use /orb join <rede> [senha] ou /orb public"
                                    + " all-ptbr para que jogadores possam entrar.");
        if (parsed.bridge().backendPortAutomatic())
            parsed = parsed.withBridge(parsed.bridge().withBackendPort(getServer().getPort()));
        if (parsed.bridge().enabled()
                && !getServer().getOnlineMode()
                && parsed.minecraftNetworks().enabled()
                && !parsed.minecraftNetworks().select().isEmpty()
                && !yaml.getBoolean("security.allow-insecure-offline-public-networks", false)) {
            throw new IllegalArgumentException("Offline-mode with public Minecraft VPN networks is unsafe. "
                    + "Enable online-mode=true, or explicitly acknowledge the risk using "
                    + "security.allow-insecure-offline-public-networks: true.");
        }
        if (parsed.bridge().enabled() && !getServer().getOnlineMode()
                && parsed.minecraftNetworks().enabled()
                && !parsed.minecraftNetworks().select().isEmpty()) {
            getLogger().warning("Offline-mode with public VPN networks allows Minecraft "
                    + "username impersonation, including OP names. Use online-mode=true.");
        }
        if (parsed.bridge().enabled()
                && getServer().getIp().isBlank()) {
            getLogger().warning("Paper is listening on all interfaces. Restrict the backend "
                    + "using firewall rules or server-ip=127.0.0.1 where appropriate.");
        }
        return parsed;
    }

    /**
     * Validates all replacements before stopping the current generation. Reload disconnects flows.
     */
    private void apply(VpnSettings replacement) throws Exception {
        synchronized (applyLock) {
            NetworkSecrets secrets = NetworkSecrets.load(getDataFolder().toPath());
            boolean transferred = false;
            try {
                secrets.validate(replacement);
                VpnRuntime previous = generations.retire();
                if (previous != null) {
                    previous.close();
                    boolean stopped = previous.awaitStopped(Duration.ofSeconds(12));
                    if (stopped) generations.retired(previous);
                    else if (!generations.stopping()) {
                        throw new IOException(
                                "previous generation has not stopped; replacement refused");
                    }
                }
                if (generations.stopping()) return;
                VpnRuntime next =
                        replacement.bridge().enabled()
                                ? new VpnRuntime(
                                        replacement,
                                        getDataFolder().toPath(),
                                        secrets,
                                        getLogger()::info)
                                : null;
                if (!generations.install(next, VpnRuntime::start)) return;
                settings = replacement;
                transferred = next != null;
            } finally {
                if (!transferred) secrets.close();
            }
        }
    }

    private boolean command(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("openradplugin.admin")) {
            sender.sendMessage("[OpenRad Plugin] Sem permissao.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "ajuda", "?" -> help(sender);
            case "join", "leave", "public", "enable", "disable" -> setup(sender, sub, args);
            case "status" -> {
                VpnRuntime r = generations.current();
                if (r == null) {
                    sender.sendMessage("[OpenRad Plugin] VPN desativada ou configuracao pendente.");
                    sender.sendMessage(
                            "[OpenRad Plugin] Para comecar: /orb join <rede> <senha> (no console)"
                                    + " ou /orb public all-ptbr. Ajuda: /orb help");
                } else {
                    var s = r.snapshot();
                    sender.sendMessage(
                            "[OpenRad Plugin] "
                                    + s.phase()
                                    + " | IP virtual: "
                                    + (s.vip().isEmpty() ? "nao atribuido" : s.vip())
                                    + " | peers: "
                                    + s.peers().stream()
                                            .filter(VpnRuntime.PeerView::connected)
                                            .count());
                    VpnSettings current = settings;
                    if (!s.vip().isEmpty() && current != null) {
                        int port = current.bridge().virtualPort();
                        sender.sendMessage(
                                "[OpenRad Plugin] Jogadores na mesma rede Radmin/OpenRad conectam em: "
                                        + s.vip()
                                        + (port == 25565 ? "" : ":" + port));
                    }
                    if (!s.lastFailure().isEmpty()) sender.sendMessage("[OpenRad Plugin] " + s.lastFailure());
                    for (String problem : r.networkProblems())
                        sender.sendMessage("[OpenRad Plugin] Rede nao conectada: " + problem);
                    var catalog = r.minecraftCatalog();
                    sender.sendMessage(
                            "[OpenRad Plugin] Minecraft: "
                                    + catalog.state()
                                    + " | catalogo: "
                                    + catalog.entries().size()
                                    + " | selecionadas: "
                                    + catalog.selected().size()
                                    + " | avisos: "
                                    + catalog.problems().size());
                    if (!catalog.problems().isEmpty())
                        sender.sendMessage("[OpenRad Plugin] " + catalog.problems().get(0));
                    var lan = r.lanSnapshot();
                    var reach = r.reachabilitySnapshot();
                    sender.sendMessage(
                            "[OpenRad Plugin] Alcance VPN: tentados "
                                    + reach.attempted()
                                    + "/"
                                    + reach.eligible()
                                    + " | autenticados nesta sessao: "
                                    + reach.authenticated()
                                    + " | aguardando nova tentativa: "
                                    + reach.backingOff());
                    sender.sendMessage(
                            "[OpenRad Plugin] Tentativas que falharam: "
                                    + reach.failures()
                                    + " "
                                    + reach.reasons());
                    sender.sendMessage(
                            "[OpenRad Plugin] LAN: "
                                    + lan.state()
                                    + " | capturados: "
                                    + lan.captured()
                                    + " | enviados: "
                                    + lan.sent()
                                    + " | rejeitados: "
                                    + lan.rejected());
                    if (s.bridge() != null)
                        sender.sendMessage(
                                "[OpenRad Plugin] TCP: "
                                        + s.bridge().connections()
                                        + " | retransmissoes: "
                                        + s.bridge().retransmissions()
                                        + " | rejeitados: "
                                        + s.bridge().rejectedFrames());
                }
            }
            case "networks" -> {
                VpnRuntime r = generations.current();
                if (r == null) sender.sendMessage("[OpenRad Plugin] VPN offline.");
                else {
                    var n = r.snapshot().networks();
                    sender.sendMessage(
                            "[OpenRad Plugin] Redes: " + (n.isEmpty() ? "nenhuma" : String.join(", ", n)));
                }
            }
            case "minecraft" -> listMinecraft(sender, args);
            case "peers" -> {
                VpnRuntime r = generations.current();
                if (r == null) sender.sendMessage("[OpenRad Plugin] VPN offline.");
                else {
                    var p =
                            r.snapshot().peers().stream()
                                    .sorted((a, b) -> Long.compareUnsigned(a.rid(), b.rid()))
                                    .toList();
                    int page;
                    try {
                        page = args.length == 1 ? 1 : Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("[OpenRad Plugin] Use /orb peers [pagina]");
                        return true;
                    }
                    int pages = Math.max(1, (p.size() + 9) / 10);
                    if (page < 1 || page > pages || args.length > 2) {
                        sender.sendMessage("[OpenRad Plugin] Pagina invalida; total: " + pages);
                        return true;
                    }
                    sender.sendMessage("[OpenRad Plugin] Peers elegiveis: " + p.size());
                    sender.sendMessage("[OpenRad Plugin] Pagina " + page + "/" + pages);
                    for (var v : p.subList((page - 1) * 10, Math.min(page * 10, p.size())))
                        sender.sendMessage(
                                "[OpenRad Plugin] "
                                        + Long.toUnsignedString(v.rid())
                                        + " "
                                        + v.name()
                                        + " "
                                        + v.vip()
                                        + " "
                                        + v.path());
                }
            }
            case "reload", "reconnect", "diagnose" -> {
                try {
                    admin.execute(
                            () -> {
                                try {
                                    if (sub.equals("diagnose")) {
                                        VpnSettings s = settings;
                                        if (s == null)
                                            throw new IllegalStateException(
                                                    "configuration unavailable");
                                        for (String result : HostDiagnostics.run(s.bridge()))
                                            reply(sender, result);
                                    } else {
                                        VpnSettings replacement =
                                                sub.equals("reload") ? readSettings() : settings;
                                        if (replacement == null) replacement = readSettings();
                                        apply(replacement);
                                        reply(
                                                sender,
                                                sub.equals("reload")
                                                        ? "Configuracao validada e aplicada."
                                                                + " Conexoes anteriores encerradas."
                                                        : "Reconexao iniciada.");
                                    }
                                } catch (Exception e) {
                                    reply(sender, "Operacao rejeitada: " + safeReason(e));
                                }
                            });
                    sender.sendMessage("[OpenRad Plugin] Operacao agendada.");
                } catch (RejectedExecutionException e) {
                    sender.sendMessage("[OpenRad Plugin] Administracao ocupada; tente novamente.");
                }
            }
            default -> {
                sender.sendMessage("[OpenRad Plugin] Subcomando desconhecido: " + sub);
                help(sender);
            }
        }
        return true;
    }

    private static void help(CommandSender sender) {
        for (String line :
                List.of(
                        "Comandos do OpenRad Plugin:",
                        "/orb status - estado, IP virtual e endereco para os jogadores",
                        "/orb join <rede> [senha] - entra numa rede (senha so pelo console;"
                                + " use aspas para nomes com espaco)",
                        "/orb leave <rede> - remove a rede da configuracao",
                        "/orb public <seletor...|off> - redes publicas Minecraft (ex.: all-ptbr)",
                        "/orb enable | disable - liga/desliga a VPN do plugin",
                        "/orb minecraft [seletor] [pagina] - catalogo de redes publicas",
                        "/orb networks | peers [pagina] - redes da sessao e peers",
                        "/orb reload | reconnect | diagnose - recarregar, reconectar, diagnosticar"))
            sender.sendMessage("[OpenRad Plugin] " + line);
    }

    /** In-game/console setup: edits config.yml/secrets.properties, then applies them. */
    private void setup(CommandSender sender, String sub, String[] rawArgs) {
        List<String> args;
        try {
            args = tokens(rawArgs);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("[OpenRad Plugin] " + e.getMessage());
            return;
        }
        String usage =
                switch (sub) {
                    case "join" -> "/orb join <rede> [senha]";
                    case "leave" -> "/orb leave <rede>";
                    case "public" -> "/orb public <seletor...|off>";
                    default -> "/orb " + sub;
                };
        boolean valid =
                switch (sub) {
                    case "join" -> args.size() == 2 || args.size() == 3;
                    case "leave" -> args.size() == 2;
                    case "public" -> args.size() >= 2;
                    default -> args.size() == 1;
                };
        if (!valid) {
            sender.sendMessage("[OpenRad Plugin] Use " + usage);
            return;
        }
        if (sub.equals("join") && args.size() == 3 && sender instanceof org.bukkit.entity.Player) {
            // Paper logs player commands with their arguments; never let a password reach logs.
            sender.sendMessage(
                    "[OpenRad Plugin] Por seguranca, informe a senha pelo console do servidor"
                            + " (comandos de jogadores ficam registrados no log). Sem senha,"
                            + " /orb join <rede> adiciona uma rede publica.");
            return;
        }
        char[] password = sub.equals("join") && args.size() == 3 ? args.get(2).toCharArray() : null;
        try {
            admin.execute(
                    () -> {
                        ConfigEditor editor = new ConfigEditor(getDataFolder().toPath());
                        try {
                            String done;
                            switch (sub) {
                                case "join" -> {
                                    var result = editor.join(args.get(1), password);
                                    done =
                                            (result.added() ? "Rede adicionada: " : "Rede atualizada: ")
                                                    + args.get(1)
                                                    + (result.secretKey().isEmpty()
                                                            ? " (publica, sem senha)"
                                                            : " (senha salva em secrets.properties)");
                                }
                                case "leave" -> {
                                    if (!editor.leave(args.get(1))) {
                                        reply(sender, "Rede nao configurada: " + args.get(1));
                                        return;
                                    }
                                    done = "Rede removida: " + args.get(1);
                                }
                                case "public" -> {
                                    List<String> selectors = args.subList(1, args.size());
                                    boolean off =
                                            selectors.size() == 1
                                                    && selectors.get(0).equalsIgnoreCase("off");
                                    editor.publicSelection(off ? List.of() : selectors);
                                    done =
                                            off
                                                    ? "Redes publicas desmarcadas (catalogo apenas listado)."
                                                    : "Redes publicas selecionadas: "
                                                            + String.join(", ", selectors);
                                }
                                default -> {
                                    editor.enabled(sub.equals("enable"));
                                    done = sub.equals("enable") ? "VPN ativada." : "VPN desativada.";
                                }
                            }
                            apply(readSettings());
                            reply(sender, done + " Configuracao aplicada; veja /orb status.");
                        } catch (Exception e) {
                            reply(sender, "Operacao rejeitada: " + safeReason(e));
                        } finally {
                            if (password != null) Arrays.fill(password, '\0');
                        }
                    });
            sender.sendMessage("[OpenRad Plugin] Operacao agendada.");
        } catch (RejectedExecutionException e) {
            if (password != null) Arrays.fill(password, '\0');
            sender.sendMessage("[OpenRad Plugin] Administracao ocupada; tente novamente.");
        }
    }

    /** Joins command arguments and splits them again, honouring "double quotes". */
    static List<String> tokens(String[] args) {
        String line = String.join(" ", args);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (c == '\\' && quoted && i + 1 < line.length()) {
                current.append(line.charAt(++i));
            } else if (c == ' ' && !quoted) {
                if (any || !current.isEmpty()) out.add(current.toString());
                current.setLength(0);
                any = false;
            } else {
                current.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("Aspas nao fechadas no comando.");
        if (any || !current.isEmpty()) out.add(current.toString());
        return out;
    }

    private List<String> configuredNetworkNames() {
        VpnSettings s = settings;
        if (s == null) return List.of();
        return s.networks().stream()
                .map(n -> n.name().contains(" ") ? "\"" + n.name() + "\"" : n.name())
                .toList();
    }

    private static List<String> publicSuggestions(VpnRuntime current) {
        List<String> base = List.of("off", "all", "all-ptbr", "all-english");
        if (current == null) return base;
        return java.util.stream.Stream.concat(
                        base.stream(),
                        MinecraftNetworks.selectors(current.minecraftCatalog().entries()).stream())
                .distinct()
                .toList();
    }

    private void listMinecraft(CommandSender sender, String[] args) {
        VpnRuntime r = generations.current();
        if (r == null) {
            sender.sendMessage("[OpenRad Plugin] VPN offline.");
            return;
        }
        try {
            if (args.length >= 2 && args[1].equalsIgnoreCase("options")) {
                int page = args.length == 2 ? 1 : Integer.parseInt(args[2]);
                var options = MinecraftNetworks.selectors(r.minecraftCatalog().entries());
                int pages = Math.max(1, (options.size() + 19) / 20);
                if (args.length > 3 || page < 1 || page > pages)
                    throw new IllegalArgumentException(
                            "Pagina de opcoes invalida; total: " + pages);
                sender.sendMessage(
                        "[OpenRad Plugin] Opcoes para vpn.minecraft-networks.select | pagina "
                                + page
                                + "/"
                                + pages);
                for (String option :
                        options.subList((page - 1) * 20, Math.min(page * 20, options.size())))
                    sender.sendMessage("[OpenRad Plugin] " + option);
                sender.sendMessage(
                        "[OpenRad Plugin] Lista completa: minecraft-network-options.yml | Exemplo: select:"
                            + " [all]");
                return;
            }
            if (args.length > 3)
                throw new IllegalArgumentException("Use /orb minecraft [seletor] [pagina]");
            String selector = "all";
            int page = 1;
            if (args.length >= 2) {
                if (args[1].matches("[0-9]+")) page = Integer.parseInt(args[1]);
                else selector = MinecraftNetworks.validateSelector(args[1]);
            }
            if (args.length == 3) page = Integer.parseInt(args[2]);
            var snapshot = r.minecraftCatalog();
            final String filter = selector;
            var entries =
                    snapshot.entries().stream()
                            .filter(e -> MinecraftNetworks.matches(e, filter))
                            .toList();
            int pages = Math.max(1, (entries.size() + 9) / 10);
            if (page < 1 || page > pages)
                throw new IllegalArgumentException("Pagina invalida; total: " + pages);
            sender.sendMessage(
                    "[OpenRad Plugin] Minecraft "
                            + snapshot.state()
                            + " | "
                            + selector
                            + " | "
                            + entries.size()
                            + " redes | pagina "
                            + page
                            + "/"
                            + pages);
            for (var entry :
                    entries.subList((page - 1) * 10, Math.min(page * 10, entries.size()))) {
                boolean joined = r.snapshot().networks().contains(entry.name());
                sender.sendMessage(
                        "[OpenRad Plugin] "
                                + entry.selector()
                                + " | "
                                + entry.name()
                                + " | membros informados: "
                                + entry.reportedCount()
                                + (joined ? " | na sessao" : ""));
            }
            sender.sendMessage(
                    "[OpenRad Plugin] Configure vpn.minecraft-networks.select e use /orb reload. Catalogo"
                            + " completo: minecraft-networks.txt");
        } catch (IllegalArgumentException e) {
            sender.sendMessage("[OpenRad Plugin] " + safeReason(e));
        }
    }

    private void reply(CommandSender sender, String text) {
        if (generations.stopping()) return;
        try {
            getServer()
                    .getScheduler()
                    .runTask(
                            this,
                            () -> {
                                if (!generations.stopping())
                                    sender.sendMessage("[OpenRad Plugin] " + text);
                            });
        } catch (IllegalStateException ignored) {
            getLogger().fine("Reply cancelled during plugin shutdown");
        }
    }

    /**
     * Messages from this plugin's validation and file handling never contain passwords, so show
     * them: an administrator must be able to see what to fix.
     */
    private static String safeReason(Exception e) {
        String m = e.getMessage();
        if (e instanceof IllegalArgumentException) return m == null ? "valor invalido" : m;
        if (e instanceof java.nio.file.NoSuchFileException)
            return "arquivo nao encontrado: " + m;
        if ((e instanceof IOException || e instanceof IllegalStateException) && m != null)
            return e.getClass().getSimpleName() + ": " + m;
        return e.getClass().getSimpleName() + "; confira os arquivos locais sem divulgar segredos";
    }

    @Override
    public void onDisable() {
        generations.stop();
        if (admin != null) admin.shutdownNow();
        GenerationGate.Handoff<VpnRuntime> handoff = generations.takeAll();
        VpnRuntime current = handoff.current();
        VpnRuntime retiring = handoff.retiring();
        if (current != null) current.close();
        if (retiring != null) retiring.close();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            if (current != null && !current.awaitStopped(remaining(deadline)))
                shutdownDeadlineReached();
            if (retiring != null && !retiring.awaitStopped(remaining(deadline)))
                shutdownDeadlineReached();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownDeadlineReached() {
        getLogger()
                .warning(
                        "Shutdown deadline reached; pending socket operations were"
                                + " cancelled.");
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }
}
