package org.faster.yetkili;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

@SuppressWarnings({"SqlNoDataSourceInspection", "deprecation"})
public final class Yetkili extends JavaPlugin implements Listener {

    private String apiUrl;
    private int pluginHttpPort;
    private int afkTimeoutSeconds;
    private HttpServer httpServer;

    private final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivityMap = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> afkStatusMap = new ConcurrentHashMap<>();
    private final Set<UUID> claimedRewards = ConcurrentHashMap.newKeySet();

    private final SecureRandom random = new SecureRandom();

    private ExecutorService asyncExecutor;

    private File pricesFile;
    private FileConfiguration pricesConfig;

    private File linksFile;
    private FileConfiguration linksConfig;

    private String dbHost, dbName, dbUser, dbPass;
    private int dbPort;

    @Override
    public void onEnable() {
        asyncExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SpikyYetkili-Async-Worker");
            t.setDaemon(true);
            return t;
        });

        saveDefaultConfig();
        loadConfigValues();
        setupDatabaseTable();

        setupPricesFile();
        setupLinksFile();

        loadLinksFromFile();
        loadLinkedAccountsFromMySQL();

        getServer().getPluginManager().registerEvents(this, this);
        startHttpServer();
        startAfkCheckerTask();

        // Paper 1.20.6 / 1.21.x / 26.2+ Uyumlu Brigadier Komut Kaydı (Sadece hesap eşle/kaldır ve reload kaldı)
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                    Commands.literal("hesap")
                            .then(Commands.literal("eşle")
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                                            ctx.getSource().getSender().sendMessage("§cBu komut sadece oyunda kullanılabilir.");
                                            return 1;
                                        }

                                        if (linkedAccounts.containsKey(player.getUniqueId())) {
                                            player.sendMessage("§cZaten Discord hesabınızla eşleşmiş bir Minecraft hesabınız var! Kaldırmak için §e/hesap kaldır §cyazabilirsiniz.");
                                            return 1;
                                        }

                                        String code = String.format("%06d", random.nextInt(1000000));

                                        asyncExecutor.submit(() -> {
                                            boolean success = sendPostRequest("/kod-olustur", String.format("{\"code\": \"%s\", \"uuid\": \"%s\", \"username\": \"%s\"}", code, player.getUniqueId(), player.getName()));

                                            Bukkit.getScheduler().runTask(this, () -> {
                                                if (!player.isOnline()) return;
                                                if (success) {
                                                    player.sendMessage("§8--------------------------------------------------");
                                                    player.sendMessage("§aDiscord hesabınızı eşleştirmek için gereken kodunuz:");
                                                    player.sendMessage("§e§l" + code);
                                                    player.sendMessage("§7Discord sunucumuzdaki kanaldan butona tıklayıp bu kodu girin.");
                                                    player.sendMessage("§cBu kod 5 dakika süreyle geçerlidir.");
                                                    player.sendMessage("§8--------------------------------------------------");
                                                } else {
                                                    player.sendMessage("§c[Hata] Discord botu API sunucusuna ulaşılamadı! Botun açık ve portun doğru olduğundan emin olun.");
                                                }
                                            });
                                        });

                                        return 1;
                                    })
                            )
                            .then(Commands.literal("kaldır")
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                                            ctx.getSource().getSender().sendMessage("§cBu komut sadece oyunda kullanılabilir.");
                                            return 1;
                                        }

                                        if (!linkedAccounts.containsKey(player.getUniqueId())) {
                                            player.sendMessage("§cZaten eşleşmiş bir Discord hesabınız bulunmuyor.");
                                            return 1;
                                        }

                                        String discordId = linkedAccounts.remove(player.getUniqueId());
                                        UUID uuid = player.getUniqueId();

                                        saveLinksToFile();

                                        asyncExecutor.submit(() -> {
                                            try (Connection conn = getConnection();
                                                 PreparedStatement ps = conn.prepareStatement("DELETE FROM linked_accounts WHERE uuid = ?")) {
                                                ps.setString(1, uuid.toString());
                                                ps.executeUpdate();
                                            } catch (SQLException e) {
                                                getLogger().severe("MySQL hesabı silinemedi: " + e.getMessage());
                                            }
                                            sendPostRequest("/hesap-kaldir", String.format("{\"uuid\": \"%s\", \"discordId\": \"%s\"}", uuid, discordId));
                                        });

                                        player.sendMessage("§aDiscord eşleşmeniz tüm sunuculardan başarıyla kaldırıldı.");
                                        return 1;
                                    })
                            )
                            .build(),
                    "Discord hesap eşleştirme yönetim komutu",
                    Collections.emptyList()
            );

            commands.register(
                    Commands.literal("spikyyetkili")
                            .then(Commands.literal("reload")
                                    .requires(source -> {
                                        CommandSender sender = source.getSender();
                                        if (sender instanceof Player player) {
                                            return player.hasPermission("spiky.admin");
                                        }
                                        return true;
                                    })
                                    .executes(ctx -> {
                                        reloadConfig();
                                        loadConfigValues();
                                        loadLinksFromFile();
                                        loadLinkedAccountsFromMySQL();
                                        if (pricesFile.exists()) {
                                            pricesConfig = YamlConfiguration.loadConfiguration(pricesFile);
                                        }
                                        ctx.getSource().getSender().sendMessage("§a[SpikyYetkili] Yapılandırma, linkler, MySQL bağlantıları ve ödüller başarıyla yenilendi!");
                                        return 1;
                                    })
                            )
                            .build(),
                    "SpikyYetkili eklenti yönetim komutu",
                    Collections.emptyList()
            );
        });

        getLogger().info("SpikyYetkili hesap eşleme ve altyapı modülüyle aktif edildi!");
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName + "?autoReconnect=true&useSSL=false&characterEncoding=utf8&serverTimezone=UTC&connectTimeout=3000&socketTimeout=3000", dbUser, dbPass);
    }

    private void setupDatabaseTable() {
        asyncExecutor.submit(() -> {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS linked_accounts (" +
                        "uuid VARCHAR(36) PRIMARY KEY, " +
                        "discord_id VARCHAR(20) NOT NULL, " +
                        "username VARCHAR(32) NOT NULL)");
            } catch (SQLException e) {
                getLogger().severe("MySQL tablo oluşturulamadı! Bağlantı bilgilerini kontrol edin: " + e.getMessage());
            }
        });
    }

    private void loadLinkedAccountsFromMySQL() {
        asyncExecutor.submit(() -> {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT uuid, discord_id FROM linked_accounts")) {
                while (rs.next()) {
                    linkedAccounts.put(UUID.fromString(rs.getString("uuid")), rs.getString("discord_id"));
                }
                getLogger().info(linkedAccounts.size() + " adet hesap eşleşmesi MySQL veritabanından yüklendi.");
            } catch (SQLException e) {
                getLogger().warning("MySQL'den hesaplar yüklenemedi: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        saveLinksToFile();
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (asyncExecutor != null) {
            asyncExecutor.shutdownNow();
        }
        linkedAccounts.clear();
        claimedRewards.clear();
        lastActivityMap.clear();
        afkStatusMap.clear();
        getLogger().info("SpikyYetkili deaktif edildi.");
    }

    private void loadConfigValues() {
        if (!new File(getDataFolder(), "config.yml").exists()) {
            getConfig().set("bot-api-url", "http://127.0.0.1:4000/api");
            getConfig().set("plugin-http-port", 4001);
            getConfig().set("afk-timeout-seconds", 300);
            getConfig().set("discord-channels.join-log", "GIRIS_KANAL_ID_BURAYA");
            getConfig().set("discord-channels.quit-log", "CIKIS_KANAL_ID_BURAYA");
            getConfig().set("discord-channels.link-log", "HESAP_ESLEME_KANAL_ID_BURAYA");

            getConfig().set("database.host", "127.0.0.1");
            getConfig().set("database.port", 3306);
            getConfig().set("database.name", "minecraft_db");
            getConfig().set("database.username", "root");
            getConfig().set("database.password", "sifreniz");
            saveConfig();
        }
        this.apiUrl = getConfig().getString("bot-api-url", "http://127.0.0.1:4000/api");
        this.pluginHttpPort = getConfig().getInt("plugin-http-port", 4001);
        this.afkTimeoutSeconds = getConfig().getInt("afk-timeout-seconds", 300);

        this.dbHost = getConfig().getString("database.host", "127.0.0.1");
        this.dbPort = getConfig().getInt("database.port", 3306);
        this.dbName = getConfig().getString("database.name", "minecraft_db");
        this.dbUser = getConfig().getString("database.username", "root");
        this.dbPass = getConfig().getString("database.password", "");
    }

    private void setupPricesFile() {
        pricesFile = new File(getDataFolder(), "prices.yml");
        if (!pricesFile.exists()) {
            try {
                if (pricesFile.createNewFile()) {
                    YamlConfiguration defConfig = new YamlConfiguration();
                    defConfig.set("link-reward.one-time-only", true);
                    defConfig.set("link-reward.message", "&aTebrikler! Discord hesabınızı başarıyla eşleştirdiğiniz için ödülünüz gönderildi.");
                    defConfig.set("link-reward.commands", List.of("eco give %player% 50000", "give %player% diamond 5"));
                    defConfig.save(pricesFile);
                }
            } catch (IOException e) {
                getLogger().severe("prices.yml oluşturulamadı: " + e.getMessage());
            }
        }
        pricesConfig = YamlConfiguration.loadConfiguration(pricesFile);
    }

    private void setupLinksFile() {
        linksFile = new File(getDataFolder(), "links.yml");
        if (!linksFile.exists()) {
            try {
                boolean created = linksFile.createNewFile();
                if (!created) {
                    getLogger().warning("links.yml dosyası zaten mevcut veya oluşturulamadı.");
                }
            } catch (IOException e) {
                getLogger().severe("links.yml oluşturulamadı: " + e.getMessage());
            }
        }
        linksConfig = YamlConfiguration.loadConfiguration(linksFile);
    }

    private void loadLinksFromFile() {
        linkedAccounts.clear();
        claimedRewards.clear();

        ConfigurationSection section = linksConfig.getConfigurationSection("links");
        if (section != null) {
            for (String uuidStr : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    String discordId = linksConfig.getString("links." + uuidStr + ".discord_id");
                    boolean claimed = linksConfig.getBoolean("links." + uuidStr + ".reward_claimed", false);

                    if (discordId != null) {
                        linkedAccounts.put(uuid, discordId);
                    }
                    if (claimed) {
                        claimedRewards.add(uuid);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void saveLinksToFile() {
        linksConfig.set("links", null);
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            String uuidStr = entry.getKey().toString();
            linksConfig.set("links." + uuidStr + ".discord_id", entry.getValue());
            linksConfig.set("links." + uuidStr + ".reward_claimed", claimedRewards.contains(entry.getKey()));
        }
        try {
            linksConfig.save(linksFile);
        } catch (IOException e) {
            getLogger().severe("links.yml kaydedilemedi: " + e.getMessage());
        }
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(pluginHttpPort), 0);

            httpServer.createContext("/mesaj-gonder", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String body = readInputStream(exchange.getRequestBody());
                String uuid = extractJsonField(body, "uuid");
                String message = extractJsonField(body, "message");

                if (uuid != null && message != null) {
                    Bukkit.getScheduler().runTask(Yetkili.this, () -> {
                        Player p = Bukkit.getPlayer(UUID.fromString(uuid));
                        if (p != null && p.isOnline()) {
                            p.sendMessage(message.replace("&", "§"));
                        }
                    });
                }

                String updateType = extractJsonField(body, "updateType");
                if ("link".equals(updateType)) {
                    String targetUuidStr = extractJsonField(body, "uuid");
                    String targetDiscordId = extractJsonField(body, "discordId");
                    String targetUsername = extractJsonField(body, "username");

                    if (targetUuidStr != null && targetDiscordId != null) {
                        UUID targetUuid = UUID.fromString(targetUuidStr);
                        linkedAccounts.put(targetUuid, targetDiscordId);

                        saveLinksToFile();

                        asyncExecutor.submit(() -> {
                            try (Connection conn = getConnection();
                                 PreparedStatement ps = conn.prepareStatement("REPLACE INTO linked_accounts (uuid, discord_id, username) VALUES (?, ?, ?)")) {
                                ps.setString(1, targetUuidStr);
                                ps.setString(2, targetDiscordId);
                                ps.setString(3, targetUsername != null ? targetUsername : "Bilinmiyor");
                                ps.executeUpdate();
                            } catch (SQLException e) {
                                getLogger().severe("MySQL eşleşme kaydedilemedi: " + e.getMessage());
                            }
                        });

                        Bukkit.getScheduler().runTask(Yetkili.this, () -> {
                            try {
                                Player p = Bukkit.getPlayer(targetUuid);
                                if (p != null && p.isOnline()) {
                                    if (pricesFile.exists()) {
                                        pricesConfig = YamlConfiguration.loadConfiguration(pricesFile);
                                    }

                                    boolean oneTimeOnly = pricesConfig.getBoolean("link-reward.one-time-only", true);
                                    boolean hasClaimed = claimedRewards.contains(targetUuid);

                                    if (!oneTimeOnly || !hasClaimed) {
                                        String msg = pricesConfig.getString("link-reward.message");
                                        if (msg != null && !msg.isEmpty()) {
                                            p.sendMessage(msg.replace("&", "§").replace("%player%", p.getName()));
                                        }

                                        List<String> rewardCommands = pricesConfig.getStringList("link-reward.commands");
                                        for (String cmd : rewardCommands) {
                                            String parsedCmd = cmd.replace("%player%", p.getName());
                                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCmd);
                                        }

                                        claimedRewards.add(targetUuid);
                                        saveLinksToFile();
                                    }

                                    String linkChannelId = getConfig().getString("discord-channels.link-log", "");
                                    asyncExecutor.submit(() ->
                                            sendPostRequest("/hesap-eslendi-log", String.format("{\"uuid\": \"%s\", \"username\": \"%s\", \"discordId\": \"%s\", \"channelId\": \"%s\"}", p.getUniqueId(), p.getName(), targetDiscordId, linkChannelId))
                                    );
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                } else if ("unlink".equals(updateType)) {
                    String targetUuid = extractJsonField(body, "uuid");
                    if (targetUuid != null) {
                        linkedAccounts.remove(UUID.fromString(targetUuid));
                        saveLinksToFile();

                        asyncExecutor.submit(() -> {
                            try (Connection conn = getConnection();
                                 PreparedStatement ps = conn.prepareStatement("DELETE FROM linked_accounts WHERE uuid = ?")) {
                                ps.setString(1, targetUuid);
                                ps.executeUpdate();
                            } catch (SQLException e) {
                                getLogger().severe("MySQL eşleşme silinemedi: " + e.getMessage());
                            }
                        });
                    }
                }

                String response = "{\"success\":true}";
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            });

            httpServer.createContext("/oyuncu-kontrol", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String body = readInputStream(exchange.getRequestBody());
                String uuidStr = extractJsonField(body, "uuid");

                boolean isOnline = false;
                if (uuidStr != null) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            isOnline = true;
                        }
                    } catch (Exception ignored) {}
                }

                String response = "{\"online\":" + isOnline + "}";
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            });

            httpServer.setExecutor(asyncExecutor);
            httpServer.start();
            getLogger().info("Dahili HTTP dinleyicisi " + pluginHttpPort + " portunda optimize edilmiş şekilde aktif.");
        } catch (Exception e) {
            getLogger().severe("HTTP sunucu başlatılamadı: " + e.getMessage());
        }
    }

    private void startAfkCheckerTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long now = System.currentTimeMillis();
            long timeoutMillis = afkTimeoutSeconds * 1000L;

            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                Long lastActivity = lastActivityMap.get(uuid);
                if (lastActivity == null) {
                    lastActivityMap.put(uuid, now);
                    continue;
                }

                boolean isAfk = afkStatusMap.getOrDefault(uuid, false);

                if (!isAfk && (now - lastActivity > timeoutMillis)) {
                    afkStatusMap.put(uuid, true);
                    player.sendMessage("§c[SpikyYetkili] Hareketsiz kaldığınız için süre sayacınız durduruldu.");
                    sendPostRequest("/afk-baslat", String.format("{\"uuid\": \"%s\"}", uuid));
                }
            }
        }, 100L, 100L);
    }

    private void updatePlayerActivity(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        lastActivityMap.put(uuid, now);

        boolean wasAfk = afkStatusMap.getOrDefault(uuid, false);
        if (wasAfk) {
            afkStatusMap.put(uuid, false);
            player.sendMessage("§a[SpikyYetkili] Hareket algılandı, süre sayacınız tekrar başlatıldı!");
            asyncExecutor.submit(() -> sendPostRequest("/afk-bitir", String.format("{\"uuid\": \"%s\"}", uuid)));
        }
    }

    private boolean sendPostRequest(String endpoint, String jsonPayload) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(apiUrl + endpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            return conn.getResponseCode() >= 200 && conn.getResponseCode() < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readInputStream(java.io.InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String extractJsonField(String json, String field) {
        try {
            String search = "\"" + field + "\":";
            int idx = json.indexOf(search);
            if (idx == -1) return null;
            int start = json.indexOf("\"", idx + search.length());
            if (start == -1) return null;
            int end = json.indexOf("\"", start + 1);
            if (end == -1) return null;
            return json.substring(start + 1, end);
        } catch (Exception e) {
            return null;
        }
    }

    private String getPlayerPrefix() {
        return "Oyuncu";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lastActivityMap.put(player.getUniqueId(), System.currentTimeMillis());
        afkStatusMap.put(player.getUniqueId(), false);

        String rank = getPlayerPrefix();
        String joinChannelId = getConfig().getString("discord-channels.join-log", "");
        String discordId = linkedAccounts.get(player.getUniqueId());

        asyncExecutor.submit(() ->
                sendPostRequest("/oyuncu-giris", String.format("{\"uuid\": \"%s\", \"username\": \"%s\", \"discordId\": \"%s\", \"rank\": \"%s\", \"channelId\": \"%s\"}",
                        player.getUniqueId(), player.getName(), discordId != null ? discordId : "Bulunmuyor", rank, joinChannelId))
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        lastActivityMap.remove(uuid);
        afkStatusMap.remove(uuid);

        String rank = getPlayerPrefix();
        String quitChannelId = getConfig().getString("discord-channels.quit-log", "");
        String discordId = linkedAccounts.get(uuid);

        asyncExecutor.submit(() ->
                sendPostRequest("/oyuncu-cikis", String.format("{\"uuid\": \"%s\", \"username\": \"%s\", \"discordId\": \"%s\", \"rank\": \"%s\", \"channelId\": \"%s\"}",
                        uuid, player.getName(), discordId != null ? discordId : "Bulunmuyor", rank, quitChannelId))
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            updatePlayerActivity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        updatePlayerActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        updatePlayerActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        updatePlayerActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        updatePlayerActivity(event.getPlayer());
    }
}