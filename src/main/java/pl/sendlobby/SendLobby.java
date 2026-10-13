package pl.sendlobby;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Plugin(
        id = "sendlobby-velocity",
        name = "SendLobby-Velocity",
        version = "1.1.0",
        description = "A simple /lobby and /hub command for Velocity.",
        authors = {"YourName"}
)
public class SendLobby {

    private static final String DEFAULT_CONFIG = """
            # SendLobby-Velocity configuration

            # Name of the lobby server (must match a server name in velocity.toml, section [servers])
            lobby-server: lobby

            # Main command (without the slash) and aliases separated by commas
            # (changing these requires a proxy restart, /lobbyreload does not apply them)
            command: lobby
            aliases: hub, l

            # Permission required to use the command. Leave empty to allow every player.
            # Example: lobby.use
            permission:

            # Permission required to use /lobbyreload (the console can always use it)
            reload-permission: sendlobby.reload

            # Print extra information to the proxy console (true or false)
            debug: true

            # Messages. Use & for colors, for example &a, &c, &e.
            message-moving: &aSending you to the lobby...
            message-already: &cYou are already in the lobby.
            message-no-server: &cThe lobby server does not exist. Please contact an administrator.
            message-players-only: &cThis command can only be used by players.
            message-reloaded: &aConfiguration reloaded.
            message-reload-failed: &cCould not reload the configuration. Check the console.
            """;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile Map<String, String> config = new HashMap<>();

    @Inject
    public SendLobby(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        loadConfig();

        String command = get("command", "lobby");
        String aliasesRaw = get("aliases", "hub, l");
        String[] aliases = aliasesRaw.isBlank()
                ? new String[0]
                : aliasesRaw.replace(" ", "").split(",");

        CommandMeta lobbyMeta = server.getCommandManager()
                .metaBuilder(command)
                .aliases(aliases)
                .plugin(this)
                .build();
        server.getCommandManager().register(lobbyMeta, new LobbyCommand());

        CommandMeta reloadMeta = server.getCommandManager()
                .metaBuilder("lobbyreload")
                .plugin(this)
                .build();
        server.getCommandManager().register(reloadMeta, new ReloadCommand());

        debug("Registered command /" + command + " with aliases " + String.join(", ", aliases));
    }

    // Returns true if the config was loaded without errors.
    private boolean loadConfig() {
        Map<String, String> loaded = new HashMap<>();
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.yml");
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
                logger.info("Created default config.yml");
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int idx = t.indexOf(':');
                if (idx < 0) continue;
                String key = t.substring(0, idx).trim();
                String value = t.substring(idx + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                loaded.put(key, value);
            }
            config = loaded;
            debug("Config loaded (" + loaded.size() + " entries). Lobby server: "
                    + get("lobby-server", "lobby"));
            return true;
        } catch (IOException ex) {
            logger.error("Could not load config.yml, keeping the previous values.", ex);
            return false;
        }
    }

    private String get(String key, String fallback) {
        return config.getOrDefault(key, fallback);
    }

    private boolean isDebug() {
        return get("debug", "true").equalsIgnoreCase("true");
    }

    private void debug(String message) {
        if (isDebug()) {
            logger.info("[DEBUG] " + message);
        }
    }

    private Component msg(String key, String fallback) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(get(key, fallback));
    }

    private class LobbyCommand implements SimpleCommand {

        @Override
        public boolean hasPermission(Invocation inv) {
            String perm = get("permission", "");
            return perm.isBlank() || inv.source().hasPermission(perm);
        }

        @Override
        public void execute(Invocation inv) {
            if (!(inv.source() instanceof Player p)) {
                inv.source().sendMessage(msg("message-players-only", "&cThis command can only be used by players."));
                debug("Command used by the console, ignoring.");
                return;
            }

            String lobbyName = get("lobby-server", "lobby");
            Optional<RegisteredServer> lobby = server.getServer(lobbyName);
            if (lobby.isEmpty()) {
                p.sendMessage(msg("message-no-server", "&cThe lobby server does not exist."));
                debug("Server '" + lobbyName + "' is not defined in velocity.toml (player: " + p.getUsername() + ").");
                return;
            }

            boolean alreadyThere = p.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equalsIgnoreCase(lobbyName))
                    .orElse(false);
            if (alreadyThere) {
                p.sendMessage(msg("message-already", "&cYou are already in the lobby."));
                debug(p.getUsername() + " is already on '" + lobbyName + "'.");
                return;
            }

            p.sendMessage(msg("message-moving", "&aSending you to the lobby..."));
            debug("Sending " + p.getUsername() + " to '" + lobbyName + "'.");
            p.createConnectionRequest(lobby.get()).fireAndForget();
        }
    }

    private class ReloadCommand implements SimpleCommand {

        @Override
        public boolean hasPermission(Invocation inv) {
            String perm = get("reload-permission", "sendlobby.reload");
            return perm.isBlank() || inv.source().hasPermission(perm);
        }

        @Override
        public void execute(Invocation inv) {
            boolean ok = loadConfig();
            if (ok) {
                inv.source().sendMessage(msg("message-reloaded", "&aConfiguration reloaded."));
            } else {
                inv.source().sendMessage(msg("message-reload-failed", "&cCould not reload the configuration."));
            }
        }
    }
}
