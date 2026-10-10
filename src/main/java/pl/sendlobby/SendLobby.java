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
        version = "1.0",
        description = "A simple /lobby and /hub command for Velocity.",
        authors = {"YourName"}
)
public class SendLobby {

    private static final String DEFAULT_CONFIG = """
            # SendLobby-Velocity configuration

            # Name of the lobby server (must match a server name in velocity.toml, section [servers])
            lobby-server: lobby

            # Main command (without the slash) and aliases separated by commas
            command: lobby
            aliases: hub, l

            # Permission required to use the command. Leave empty to allow every player.
            # Example: lobby.use
            permission:

            # Messages. Use & for colors, for example &a, &c, &e.
            message-moving: &aSending you to the lobby...
            message-already: &cYou are already in the lobby.
            message-no-server: &cThe lobby server does not exist. Please contact an administrator.
            message-players-only: &cThis command can only be used by players.
            """;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<String, String> config = new HashMap<>();

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

        CommandMeta meta = server.getCommandManager()
                .metaBuilder(command)
                .aliases(aliases)
                .plugin(this)
                .build();
        server.getCommandManager().register(meta, new LobbyCommand());
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.yml");
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_CONFIG, StandardCharsets.UTF_8);
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
                config.put(key, value);
            }
        } catch (IOException ex) {
            logger.error("Could not load config.yml, using default values.", ex);
        }
    }

    private String get(String key, String fallback) {
        return config.getOrDefault(key, fallback);
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
                return;
            }

            String lobbyName = get("lobby-server", "lobby");
            Optional<RegisteredServer> lobby = server.getServer(lobbyName);
            if (lobby.isEmpty()) {
                p.sendMessage(msg("message-no-server", "&cThe lobby server does not exist."));
                return;
            }

            boolean alreadyThere = p.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equalsIgnoreCase(lobbyName))
                    .orElse(false);
            if (alreadyThere) {
                p.sendMessage(msg("message-already", "&cYou are already in the lobby."));
                return;
            }

            p.sendMessage(msg("message-moving", "&aSending you to the lobby..."));
            p.createConnectionRequest(lobby.get()).fireAndForget();
        }
    }
}