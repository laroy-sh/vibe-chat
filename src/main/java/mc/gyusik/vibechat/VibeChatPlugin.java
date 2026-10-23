package mc.gyusik.vibechat;

import net.kyori.adventure.audience.Audience;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.NameTagVisibility;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VibeChatPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("(?i)[&\u00a7]([0-9a-fk-orx]|#[0-9a-f]{6})");
    private static final Pattern LINK_PATTERN = Pattern.compile("(?i)\\b((https?://)?([a-z0-9-]+\\.)+[a-z]{2,}|\\d{1,3}(\\.\\d{1,3}){3})(/\\S*)?");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("(^|\\s)(/[^\\s]+)");
    private static final String TEAM_INVISE = "vc_hidden_names";

    private FileConfiguration settings;
    private FileConfiguration messages;
    private FileConfiguration badwords;
    private FileConfiguration ignores;
    private FileConfiguration mutes;
    private FileConfiguration offlineMessages;
    private FileConfiguration preferences;
    private File ignoresFile;
    private File mutesFile;
    private File offlineMessagesFile;
    private File preferencesFile;

    private Object vaultChat;
    private Method vaultPrefixPlayer;
    private Method vaultSuffixPlayer;
    private Method vaultPrefixWorldPlayer;
    private Method vaultSuffixWorldPlayer;
    private Method papiSetPlaceholders;

    private final Map<UUID, ChatMode> modes = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> privateTargets = new ConcurrentHashMap<>();
    private final Map<UUID, ReplySelection> publicReplyTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChatTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> repeatCounts = new ConcurrentHashMap<>();
    private final Map<UUID, String> recentChatMessages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentChatTimes = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> burstWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Long> burstWarnTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fastWarnTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivityTimes = new ConcurrentHashMap<>();
    private final Set<String> normalizedBadWords = ConcurrentHashMap.newKeySet();
    private final Set<String> normalizedBadRoots = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfigFiles();
        reloadEverything();
        hookIntegrations();
        Bukkit.getPluginManager().registerEvents(this, this);
        register("vibechat");
        register("local");
        register("global");
        register("msg");
        register("reply");
        register("ignore");
        register("unignore");
        register("mute");
        register("tempmute");
        register("unmute");
        register("discord");
        register("telegram");
        register("site");
        getLogger().info("VibeChat enabled.");
    }

    @Override
    public void onDisable() {
        saveQuietly(ignores, ignoresFile);
        saveQuietly(mutes, mutesFile);
        saveQuietly(offlineMessages, offlineMessagesFile);
        saveQuietly(preferences, preferencesFile);
    }

    private void register(String name) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    private void saveDefaultConfigFiles() {
        saveResourceIfMissing("settings.yml");
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("badwords.yml");
        File dataDir = new File(getDataFolder(), "data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        ignoresFile = new File(dataDir, "ignores.yml");
        mutesFile = new File(dataDir, "mutes.yml");
        offlineMessagesFile = new File(dataDir, "offline-messages.yml");
        preferencesFile = new File(dataDir, "preferences.yml");
        createIfMissing(ignoresFile);
        createIfMissing(mutesFile);
        createIfMissing(offlineMessagesFile);
        createIfMissing(preferencesFile);
    }

    private void saveResourceIfMissing(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private void createIfMissing(File file) {
        try {
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                file.createNewFile();
            }
        } catch (IOException e) {
            getLogger().warning("Cannot create " + file.getName() + ": " + e.getMessage());
        }
    }

    private void reloadEverything() {
        settings = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "settings.yml"));
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        badwords = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "badwords.yml"));
        ignores = YamlConfiguration.loadConfiguration(ignoresFile);
        mutes = YamlConfiguration.loadConfiguration(mutesFile);
        offlineMessages = YamlConfiguration.loadConfiguration(offlineMessagesFile);
        preferences = YamlConfiguration.loadConfiguration(preferencesFile);
        rebuildBadWordCache();
    }

    private void rebuildBadWordCache() {
        normalizedBadWords.clear();
        normalizedBadRoots.clear();
        for (String word : badwords.getStringList("words")) {
            String normalized = normalizeDirtyToken(word);
            if (normalized.length() >= 3) {
                normalizedBadWords.add(normalized);
            }
        }
        for (String root : badwords.getStringList("roots")) {
            String normalized = normalizeDirtyToken(root);
            if (normalized.length() >= 3) {
                normalizedBadRoots.add(normalized);
            }
        }
        if (normalizedBadRoots.isEmpty()) {
            normalizedBadRoots.addAll(List.of("хуй", "хуе", "хуё", "пизд", "пзд", "еба", "еби", "ебу", "ебл", "ебн", "ебш", "ёб", "бля", "сук", "суч", "пидор", "пидр"));
        }
    }

    private void hookIntegrations() {
        vaultChat = null;
        vaultPrefixPlayer = null;
        vaultSuffixPlayer = null;
        vaultPrefixWorldPlayer = null;
        vaultSuffixWorldPlayer = null;
        papiSetPlaceholders = null;
        try {
            Class<?> chatClass = Class.forName("net.milkbowl.vault.chat.Chat");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(chatClass);
            if (registration != null) {
                vaultChat = registration.getProvider();
                vaultPrefixPlayer = findMethod(vaultChat.getClass(), "getPlayerPrefix", Player.class);
                vaultSuffixPlayer = findMethod(vaultChat.getClass(), "getPlayerSuffix", Player.class);
                vaultPrefixWorldPlayer = findMethod(vaultChat.getClass(), "getPlayerPrefix", String.class, OfflinePlayer.class);
                vaultSuffixWorldPlayer = findMethod(vaultChat.getClass(), "getPlayerSuffix", String.class, OfflinePlayer.class);
            }
        } catch (Throwable ignored) {
            vaultChat = null;
        }

        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            papiSetPlaceholders = papi.getMethod("setPlaceholders", Player.class, String.class);
        } catch (Throwable ignored) {
            papiSetPlaceholders = null;
        }
    }

    private Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            Method method = type.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String raw = PLAIN.serialize(event.message()).trim();
        if (raw.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (markRecentChat(player, raw)) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> handleChat(player, raw));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String raw = event.getMessage().trim();
        if (raw.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (markRecentChat(player, raw)) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> handleChat(player, raw));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loadPreferences(player);
        if (isInvise(player.getUniqueId())) {
            applyInvise(player, true);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> showMailSummary(player), 20L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw.length() <= 1) {
            return;
        }
        String[] split = raw.substring(1).trim().split("\\s+");
        if (split.length == 0) {
            return;
        }
        String label = split[0].toLowerCase(Locale.ROOT);
        int namespaceEnd = label.indexOf(':');
        if (namespaceEnd > 0 && Set.of("minecraft", "bukkit", "vibechat").contains(label.substring(0, namespaceEnd))) {
            label = label.substring(namespaceEnd + 1);
        }
        String[] args = Arrays.copyOfRange(split, 1, split.length);
        if (Set.of("me", "teammsg", "tm").contains(label) && isMuted(event.getPlayer())) {
            event.setCancelled(true);
            send(event.getPlayer(), msg("muted").replace("{time}", formatMuteTime(event.getPlayer())));
            play(event.getPlayer(), "sounds.error");
            return;
        }
        if (Set.of("vc", "vibechat").contains(label)) {
            event.setCancelled(true);
            commandVc(event.getPlayer(), args);
            return;
        }
        if (label.equals("local")) {
            event.setCancelled(true);
            switchMode(event.getPlayer(), ChatMode.LOCAL);
            return;
        }
        if (label.equals("global")) {
            event.setCancelled(true);
            switchMode(event.getPlayer(), ChatMode.GLOBAL);
            return;
        }
        if (Set.of("msg", "m", "w", "tell", "t", "message").contains(label)) {
            event.setCancelled(true);
            commandMsg(event.getPlayer(), args);
            return;
        }
        if (Set.of("reply", "r").contains(label)) {
            event.setCancelled(true);
            commandReply(event.getPlayer(), args);
            return;
        }
        if (label.equals("ignore")) {
            event.setCancelled(true);
            commandIgnore(event.getPlayer(), args, true);
            return;
        }
        if (label.equals("unignore")) {
            event.setCancelled(true);
            commandIgnore(event.getPlayer(), args, false);
            return;
        }
        if (label.equals("mute") || label.equals("tempmute")) {
            event.setCancelled(true);
            commandMute(event.getPlayer(), args, 0);
            return;
        }
        if (label.equals("unmute")) {
            event.setCancelled(true);
            commandUnmute(event.getPlayer(), args, 0);
            return;
        }
        if (Set.of("discord", "telegram", "site").contains(label)) {
            event.setCancelled(true);
            commandLink(event.getPlayer(), label);
        }
    }

    private void handleChat(Player player, String raw) {
        resetIdleMode(player);
        if (isMuted(player)) {
            send(player, msg("muted").replace("{time}", formatMuteTime(player)));
            play(player, "sounds.error");
            return;
        }

        if (raw.equals("!!")) {
            modes.put(player.getUniqueId(), ChatMode.LOCAL);
            privateTargets.remove(player.getUniqueId());
            publicReplyTargets.remove(player.getUniqueId());
            touch(player);
            send(player, msg("chat-local"));
            return;
        }

        if (raw.equals("!")) {
            privateTargets.remove(player.getUniqueId());
            publicReplyTargets.remove(player.getUniqueId());
            modes.put(player.getUniqueId(), ChatMode.GLOBAL);
            touch(player);
            send(player, msg("chat-global"));
            return;
        }

        if (privateTargets.containsKey(player.getUniqueId()) && !raw.startsWith("!")) {
            sendPrivate(player, privateTargets.get(player.getUniqueId()), raw);
            touch(player);
            return;
        }

        ReplySelection publicReply = publicReplyTargets.remove(player.getUniqueId());
        ChatMode chatMode = publicReply == null ? mode(player) : publicReply.mode;
        String message = raw;
        if (raw.startsWith("!") && raw.length() > 1) {
            chatMode = ChatMode.GLOBAL;
            message = raw.substring(1).trim();
            publicReply = null;
        }

        String plainMessage = stripColorCodes(message).trim();
        if (plainMessage.isEmpty()) {
            return;
        }
        if (!passesModeration(player, plainMessage)) {
            return;
        }

        String formattedMessage = prepareMessage(player, message);
        if (chatMode == ChatMode.GLOBAL) {
            sendGlobal(player, formattedMessage, publicReply);
        } else {
            sendLocal(player, formattedMessage, publicReply);
        }
        touch(player);
    }

    private void resetIdleMode(Player player) {
        long last = lastActivityTimes.getOrDefault(player.getUniqueId(), 0L);
        if (last <= 0L) {
            lastActivityTimes.put(player.getUniqueId(), System.currentTimeMillis());
            return;
        }
        long idleMillis = settings.getLong("modes.idle-reset-seconds", 90L) * 1000L;
        if (idleMillis > 0 && System.currentTimeMillis() - last >= idleMillis) {
            modes.put(player.getUniqueId(), ChatMode.LOCAL);
            privateTargets.remove(player.getUniqueId());
        }
    }

    private void touch(Player player) {
        lastActivityTimes.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private boolean markRecentChat(Player player, String raw) {
        long now = System.currentTimeMillis();
        String normalized = normalize(raw);
        String last = recentChatMessages.get(player.getUniqueId());
        long lastTime = recentChatTimes.getOrDefault(player.getUniqueId(), 0L);
        if (normalized.equals(last) && now - lastTime < 250L) {
            return true;
        }
        recentChatMessages.put(player.getUniqueId(), normalized);
        recentChatTimes.put(player.getUniqueId(), now);
        return false;
    }

    private boolean passesModeration(Player player, String message) {
        if (!hasBypass(player, "links") && settings.getBoolean("moderation.block-links", true) && LINK_PATTERN.matcher(message).find()) {
            send(player, msg("link-blocked"));
            play(player, "sounds.error");
            return false;
        }

        long now = System.currentTimeMillis();
        if (!hasBypass(player, "spam")) {
            long last = lastChatTime.getOrDefault(player.getUniqueId(), 0L);
            if (now - last < settings.getLong("moderation.anti-spam-ms", 1200L)) {
                warnTooFast(player, now);
                return false;
            }
            lastChatTime.put(player.getUniqueId(), now);
            if (!passesBurstLimit(player, now)) {
                return false;
            }
        }

        if (!hasBypass(player, "repeat")) {
            String normalized = normalize(message);
            long lastTime = lastMessageTime.getOrDefault(player.getUniqueId(), 0L);
            String last = lastMessage.get(player.getUniqueId());
            long repeatWindow = settings.getLong("moderation.repeat-block-seconds", 30L) * 1000L;
            if (normalized.equals(last) && now - lastTime < repeatWindow) {
                int count = repeatCounts.merge(player.getUniqueId(), 1, Integer::sum);
                if (count >= settings.getInt("moderation.repeat-warn-on", 3)) {
                    send(player, msg("not-spam"));
                    play(player, "sounds.error");
                    repeatCounts.put(player.getUniqueId(), 0);
                }
                return false;
            }
            lastMessage.put(player.getUniqueId(), normalized);
            lastMessageTime.put(player.getUniqueId(), now);
            repeatCounts.put(player.getUniqueId(), 0);
        }
        return true;
    }

    private void warnTooFast(Player player, long now) {
        long warnCooldown = settings.getLong("moderation.fast-warn-cooldown-ms", 1200L);
        long lastWarn = fastWarnTimes.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastWarn >= warnCooldown) {
            send(player, msg("too-fast"));
            play(player, "sounds.error");
            fastWarnTimes.put(player.getUniqueId(), now);
        }
    }

    private boolean passesBurstLimit(Player player, long now) {
        int max = settings.getInt("moderation.burst.max-messages", 5);
        long windowMillis = settings.getLong("moderation.burst.window-seconds", 6L) * 1000L;
        if (max <= 0 || windowMillis <= 0) {
            return true;
        }
        List<Long> window = burstWindows.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
        window.removeIf(time -> now - time > windowMillis);
        if (window.size() >= max) {
            long lastWarn = burstWarnTimes.getOrDefault(player.getUniqueId(), 0L);
            long warnCooldown = settings.getLong("moderation.burst.warn-cooldown-seconds", 4L) * 1000L;
            if (now - lastWarn >= warnCooldown) {
                send(player, msg("slow-down"));
                play(player, "sounds.error");
                burstWarnTimes.put(player.getUniqueId(), now);
            }
            return false;
        }
        window.add(now);
        return true;
    }

    private String filterBadWords(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String result = message;
        String phraseReplacement = settings.getString("moderation.badphrase-replacement", "#!?#");
        for (String phrase : badwords.getStringList("phrases")) {
            if (!phrase.isBlank()) {
                result = result.replaceAll("(?iu)" + Pattern.quote(phrase), Matcher.quoteReplacement(phraseReplacement));
            }
        }
        for (String word : badwords.getStringList("words")) {
            if (!word.isBlank()) {
                result = replaceBadWord(result, word);
            }
        }
        result = replaceObfuscatedBadWords(result);
        return result;
    }

    private String replaceBadWord(String text, String badWord) {
        Pattern pattern = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(badWord) + "(?![\\p{L}\\p{N}_])");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(maskWord(matcher.group())));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String maskWord(String word) {
        if (word.length() <= 2) {
            return settings.getString("moderation.badword-replacement", "п***");
        }
        return word.charAt(0) + "***";
    }

    private String replaceObfuscatedBadWords(String message) {
        String[] parts = message.split("((?<=\\s)|(?=\\s))", -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                result.append(part);
                continue;
            }
            if (isDirtyToken(part)) {
                result.append(maskWord(firstVisibleLetters(part)));
            } else {
                result.append(part);
            }
        }
        return result.toString();
    }

    private boolean isDirtyToken(String token) {
        String normalized = normalizeDirtyToken(token);
        if (normalized.length() < 3) {
            return false;
        }
        if (normalizedBadWords.contains(normalized)) {
            return true;
        }
        for (String root : normalizedBadRoots) {
            if (normalized.contains(root)) {
                return true;
            }
        }
        return false;
    }

    private String firstVisibleLetters(String token) {
        String letters = token.replaceAll("[^\\p{L}\\p{N}]+", "");
        return letters.isEmpty() ? token : letters;
    }

    private String normalizeDirtyToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('0', 'о')
                .replace('3', 'з')
                .replace('6', 'б')
                .replace('9', 'я')
                .replace('@', 'а')
                .replace('1', 'и')
                .replace('!', 'и')
                .replace('|', 'и')
                .replace('x', 'х')
                .replace('y', 'у')
                .replace('u', 'и')
                .replace('i', 'и')
                .replace('a', 'а')
                .replace('e', 'е')
                .replace('o', 'о')
                .replace('p', 'п')
                .replace('c', 'с')
                .replace('k', 'к')
                .replace('m', 'м')
                .replace('t', 'т')
                .replace('b', 'б');
        lower = lower.replaceAll("[^\\p{L}\\p{N}]+", "");
        StringBuilder collapsed = new StringBuilder();
        char previous = 0;
        int repeats = 0;
        for (int i = 0; i < lower.length(); i++) {
            char current = lower.charAt(i);
            if (current == previous) {
                repeats++;
                if (repeats <= 1) {
                    collapsed.append(current);
                }
            } else {
                previous = current;
                repeats = 0;
                collapsed.append(current);
            }
        }
        return collapsed.toString();
    }

    private void sendGlobal(Player sender, String message, ReplySelection publicReplyTarget) {
        Component component = chatComponent(sender, message, true, publicReplyTarget);
        Component bedrockComponent = bedrockSafeComponent(component);
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!isIgnoring(target.getUniqueId(), sender.getUniqueId())) {
                sendChatComponent(target, component, bedrockComponent);
                if (!target.equals(sender) && soundsEnabled(target)) {
                    play(target, "sounds.global");
                }
            }
        }
    }

    private void sendLocal(Player sender, String message, ReplySelection publicReplyTarget) {
        Component component = chatComponent(sender, message, false, publicReplyTarget);
        Component bedrockComponent = bedrockSafeComponent(component);
        double radius = settings.getDouble("channels.local-radius", 126.0);
        double radiusSquared = radius * radius;
        boolean heardBySomeone = false;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!target.getWorld().equals(sender.getWorld())) {
                continue;
            }
            if (target.getLocation().distanceSquared(sender.getLocation()) > radiusSquared) {
                continue;
            }
            if (isIgnoring(target.getUniqueId(), sender.getUniqueId())) {
                continue;
            }
            sendChatComponent(target, component, bedrockComponent);
            if (!target.equals(sender)) {
                heardBySomeone = true;
            }
        }
        if (!heardBySomeone) {
            send(sender, msg("no-one-heard"));
        }
    }

    /**
     * Sends a clan-scoped message through VibeChat formatting and moderation.
     *
     * @param sender player who wrote the message
     * @param recipients online clan members who should receive the message
     * @param clanTag formatted clan tag shown near the player name
     * @param rawMessage raw message text
     * @return true when the message passed moderation and was sent
     */
    public boolean sendClanMessage(Player sender, Collection<? extends Player> recipients, String clanTag, String rawMessage) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipients, "recipients");
        Objects.requireNonNull(rawMessage, "rawMessage");

        resetIdleMode(sender);
        if (isMuted(sender)) {
            send(sender, msg("muted").replace("{time}", formatMuteTime(sender)));
            play(sender, "sounds.error");
            return false;
        }

        String message = rawMessage.trim();
        String plainMessage = stripColorCodes(message).trim();
        if (plainMessage.isEmpty() || !passesModeration(sender, plainMessage)) {
            return false;
        }

        Component component = clanChatComponent(sender, Objects.toString(clanTag, ""), prepareMessage(sender, message));
        Component bedrockComponent = bedrockSafeComponent(component);
        for (Player target : recipients) {
            if (target != null && target.isOnline() && !isIgnoring(target.getUniqueId(), sender.getUniqueId())) {
                sendChatComponent(target, component, bedrockComponent);
            }
        }
        touch(sender);
        return true;
    }

    private void sendChatComponent(Player target, Component javaComponent, Component bedrockComponent) {
        target.sendMessage(isFloodgatePlayer(target) ? bedrockComponent : javaComponent);
    }

    private Component bedrockSafeComponent(Component component) {
        return LEGACY_SECTION.deserialize(LEGACY_SECTION.serialize(component));
    }

    private boolean isFloodgatePlayer(Player player) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Component chatComponent(Player sender, String message, boolean global, ReplySelection publicReplyTarget) {
        String label = settings.getString(global ? "channels.global-label" : "channels.local-label", global ? "&e[Мир]" : "&a[Локал]");
        String format = settings.getString(global ? "channels.global-format" : "channels.local-format");
        PublicReply publicReply = publicReply(publicReplyTarget);
        String rendered = format
                .replace("{label}", "__LABEL__")
                .replace("{prefix}", prefix(sender))
                .replace("{player}", "__PLAYER__")
                .replace("{suffix}", suffix(sender))
                .replace("{reply}", publicReply == null ? "" : "__PUBLIC_REPLY__")
                .replace("{message}", "__MESSAGE__");
        rendered = applyPlaceholders(sender, rendered);
        String[] parts = rendered.split(Pattern.quote("__MESSAGE__"), -1);
        TextComponent.Builder builder = Component.text();
        if (parts.length > 0) {
            appendDecorated(builder, parts[0], label, global, sender.getName(), publicReply);
        }
        builder.append(messageComponent(sender, message, global ? ReplyClickMode.GLOBAL : ReplyClickMode.LOCAL, canUseColor(sender)));
        if (parts.length > 1) {
            appendDecorated(builder, parts[1], label, global, sender.getName(), publicReply);
        }
        return builder.build();
    }

    private Component clanChatComponent(Player sender, String clanTag, String message) {
        String label = settings.getString("channels.clan-label", "&b[Клан]");
        String format = settings.getString("channels.clan-format",
                "{label} {clan} {prefix}{player}{suffix} &8➜ &f{message}");
        String rendered = format
                .replace("{label}", "__LABEL__")
                .replace("{clan}", "__CLAN__")
                .replace("{prefix}", prefix(sender))
                .replace("{player}", "__PLAYER__")
                .replace("{suffix}", suffix(sender))
                .replace("{message}", "__MESSAGE__");
        rendered = applyPlaceholders(sender, rendered);

        String[] parts = rendered.split(Pattern.quote("__MESSAGE__"), -1);
        TextComponent.Builder builder = Component.text();
        if (parts.length > 0) {
            appendClanDecorated(builder, parts[0], label, sender.getName(), clanTag);
        }
        builder.append(playerText(message, canUseColor(sender)));
        if (parts.length > 1) {
            appendClanDecorated(builder, parts[1], label, sender.getName(), clanTag);
        }
        return builder.build();
    }

    private void appendDecorated(TextComponent.Builder builder, String text, String label, boolean global, String playerName, PublicReply publicReply) {
        String[] labelParts = text.split(Pattern.quote("__LABEL__"), -1);
        for (int i = 0; i < labelParts.length; i++) {
            appendWithPlayerAndReply(builder, labelParts[i], playerName, publicReply);
            if (i < labelParts.length - 1) {
                builder.append(labelComponent(label, global));
            }
        }
    }

    private void appendClanDecorated(TextComponent.Builder builder, String text, String label, String playerName, String clanTag) {
        String[] labelParts = text.split(Pattern.quote("__LABEL__"), -1);
        for (int i = 0; i < labelParts.length; i++) {
            appendWithClanAndPlayer(builder, labelParts[i], playerName, clanTag);
            if (i < labelParts.length - 1) {
                builder.append(color(label));
            }
        }
    }

    private void appendWithClanAndPlayer(TextComponent.Builder builder, String text, String playerName, String clanTag) {
        String[] clanParts = text.split(Pattern.quote("__CLAN__"), -1);
        for (int i = 0; i < clanParts.length; i++) {
            appendWithPlayer(builder, clanParts[i], playerName);
            if (i < clanParts.length - 1 && !clanTag.isBlank()) {
                builder.append(color(clanTag));
            }
        }
    }

    private void appendWithPlayerAndReply(TextComponent.Builder builder, String text, String playerName, PublicReply publicReply) {
        String[] replyParts = text.split(Pattern.quote("__PUBLIC_REPLY__"), -1);
        for (int i = 0; i < replyParts.length; i++) {
            appendWithPlayer(builder, replyParts[i], playerName);
            if (i < replyParts.length - 1 && publicReply != null) {
                builder.append(publicReplyComponent(publicReply));
            }
        }
    }

    private void appendWithPlayer(TextComponent.Builder builder, String text, String playerName) {
        String[] playerParts = text.split(Pattern.quote("__PLAYER__"), -1);
        for (int i = 0; i < playerParts.length; i++) {
            if (!playerParts[i].isEmpty()) {
                builder.append(color(playerParts[i]));
            }
            if (i < playerParts.length - 1) {
                builder.append(playerNameComponent(playerName));
            }
        }
    }

    private Component labelComponent(String label, boolean global) {
        String hover = global ? "&eНажмите, чтобы писать в мировой чат" : "&eНажмите, чтобы писать в локальный чат";
        return color(label)
                .clickEvent(ClickEvent.callback(audience -> runOnPlayer(audience, player -> switchMode(player, global ? ChatMode.GLOBAL : ChatMode.LOCAL))))
                .hoverEvent(HoverEvent.showText(color(hover)));
    }

    private Component messageComponent(Player sender, String message, ReplyClickMode replyClickMode, boolean allowColor) {
        TextComponent.Builder builder = Component.text();
        Matcher matcher = COMMAND_PATTERN.matcher(message);
        int last = 0;
        while (matcher.find()) {
            String before = message.substring(last, matcher.start(2));
            if (!before.isEmpty()) {
                builder.append(replyClickMode == ReplyClickMode.NONE ? playerText(before, allowColor) : publicReplyClickable(sender, before, replyClickMode, allowColor));
            }
            String command = matcher.group(2);
            builder.append(Component.text(command)
                    .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand(command))
                    .hoverEvent(HoverEvent.showText(color("&eНажмите, чтобы вставить команду"))));
            last = matcher.end(2);
        }
        if (last < message.length()) {
            String tail = message.substring(last);
            builder.append(replyClickMode == ReplyClickMode.NONE ? playerText(tail, allowColor) : publicReplyClickable(sender, tail, replyClickMode, allowColor));
        }
        Component base = builder.build();
        if (settings.getBoolean("features.mentions", true)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(sender) && containsWord(message, online.getName())) {
                    if (soundsEnabled(online)) {
                        play(online, "sounds.mention");
                    }
                }
            }
        }
        return base;
    }

    private Component publicReplyClickable(Player sender, String text, ReplyClickMode replyClickMode, boolean allowColor) {
        return playerText(text, allowColor)
                .clickEvent(ClickEvent.callback(audience -> runOnPlayer(audience, player -> selectPublicReply(player, sender.getUniqueId().toString(), replyClickMode.name().toLowerCase(Locale.ROOT)))))
                .hoverEvent(HoverEvent.showText(color(replyClickMode == ReplyClickMode.PRIVATE
                        ? "&eОтветить игроку &f" + sender.getName() + " &eв ЛС"
                        : "&eОтветить игроку &f" + sender.getName() + " &eв этом чате")));
    }

    private Component publicReplyComponent(PublicReply reply) {
        String label = settings.getString("channels.public-reply-label", "&b[для]");
        return color(" &8- " + label + " &f" + reply.name);
    }

    private PublicReply publicReply(ReplySelection selection) {
        if (selection == null) {
            return null;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(selection.targetId);
        String name = target.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        return new PublicReply(selection.targetId, name);
    }

    private void sendPrivate(Player sender, UUID receiverId, String rawMessage) {
        if (isMuted(sender)) {
            send(sender, msg("muted").replace("{time}", formatMuteTime(sender)));
            play(sender, "sounds.error");
            return;
        }
        Player receiver = Bukkit.getPlayer(receiverId);
        if (receiverId.equals(sender.getUniqueId())) {
            send(sender, msg("cannot-self"));
            play(sender, "sounds.error");
            return;
        }
        if (isIgnoring(sender.getUniqueId(), receiverId)) {
            send(sender, msg("ignored-by-you"));
            play(sender, "sounds.error");
            return;
        }
        if (isIgnoring(receiverId, sender.getUniqueId())) {
            send(sender, msg("ignored-by-target"));
            play(sender, "sounds.error");
            return;
        }
        String plainMessage = stripColorCodes(rawMessage).trim();
        if (plainMessage.isEmpty() || !passesModeration(sender, plainMessage)) {
            return;
        }
        String message = prepareMessage(sender, rawMessage);
        OfflinePlayer receiverOffline = Bukkit.getOfflinePlayer(receiverId);
        if (receiver == null) {
            storeOfflineMessage(sender, receiverOffline, message);
            return;
        }

        privateTargets.put(sender.getUniqueId(), receiver.getUniqueId());
        replyTargets.put(sender.getUniqueId(), receiver.getUniqueId());
        replyTargets.put(receiver.getUniqueId(), sender.getUniqueId());

        Component in = privateComponent(sender, receiver, message, true);
        Component out = privateComponent(sender, receiver, message, false);
        sendChatComponent(receiver, in, bedrockSafeComponent(in));
        sendChatComponent(sender, out, bedrockSafeComponent(out));
        play(receiver, "sounds.private-message");
    }

    private Component privateComponent(Player sender, Player receiver, String message, boolean incoming) {
        String format = incoming
                ? settings.getString("private-messages.format-in")
                : settings.getString("private-messages.format-out");
        String playerName = incoming ? sender.getName() : receiver.getName();
        String rendered = format
                .replace("{sender}", sender.getName())
                .replace("{receiver}", receiver.getName())
                .replace("{message}", "__MESSAGE__");
        String[] parts = rendered.split(Pattern.quote(playerName), 2);
        TextComponent.Builder builder = Component.text();
        if (parts.length == 2) {
            builder.append(color(parts[0]));
            builder.append(playerNameComponent(playerName));
            String[] messageParts = parts[1].split(Pattern.quote("__MESSAGE__"), -1);
            builder.append(color(messageParts[0]));
            Player replyTarget = incoming ? sender : receiver;
            builder.append(messageComponent(replyTarget, message, ReplyClickMode.PRIVATE, canUseColor(sender)));
            if (messageParts.length > 1) {
                builder.append(color(messageParts[1]));
            }
            return builder.build();
        }
        String[] messageParts = rendered.split(Pattern.quote("__MESSAGE__"), -1);
        builder.append(color(messageParts[0]));
        for (int i = 1; i < messageParts.length; i++) {
            builder.append(playerText(message, canUseColor(sender)));
            builder.append(color(messageParts[i]));
        }
        return builder.build();
    }

    private Component playerNameComponent(String playerName) {
        return Component.text(playerName)
                .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand("/t " + playerName + " "))
                .hoverEvent(HoverEvent.showText(color("&eНаписать игроку &f" + playerName)));
    }

    private void storeOfflineMessage(Player sender, OfflinePlayer receiver, String message) {
        String key = receiver.getUniqueId().toString();
        int limit = settings.getInt("private-messages.offline-limit", 5);
        List<Map<?, ?>> messagesForPlayer = offlineMessages.getMapList(key);
        if (messagesForPlayer.size() >= limit) {
            send(sender, msg("offline-full"));
            play(sender, "sounds.error");
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sender-id", sender.getUniqueId().toString());
        entry.put("sender-name", sender.getName());
        entry.put("message", message);
        entry.put("color", canUseColor(sender));
        entry.put("time", System.currentTimeMillis());
        List<Map<?, ?>> updated = new ArrayList<>(messagesForPlayer);
        updated.add(entry);
        offlineMessages.set(key, updated);
        saveQuietly(offlineMessages, offlineMessagesFile);
        int left = Math.max(0, limit - updated.size());
        send(sender, msg("offline-stored").replace("{left}", String.valueOf(left)));
    }

    private void showMailSummary(Player player) {
        String key = player.getUniqueId().toString();
        List<Map<?, ?>> list = offlineMessages.getMapList(key);
        if (list.isEmpty()) {
            return;
        }
        send(player, msg("mail-header"));
        Map<String, MailSender> senders = new LinkedHashMap<>();
        for (Map<?, ?> map : list) {
            Object senderIdValue = map.get("sender-id");
            Object senderNameValue = map.get("sender-name");
            if (senderIdValue == null) {
                continue;
            }
            String senderId = String.valueOf(senderIdValue);
            String senderName = senderNameValue == null ? "Игрок" : String.valueOf(senderNameValue);
            senders.computeIfAbsent(senderId, ignored -> new MailSender(senderName)).count++;
        }
        TextComponent.Builder row = Component.text().append(color(settings.getString("prefix", "&8[&bЧат&8] &r")));
        int inRow = 0;
        for (Map.Entry<String, MailSender> entry : senders.entrySet()) {
            String senderId = entry.getKey();
            MailSender mail = entry.getValue();
            if (inRow >= 5) {
                player.sendMessage(row.build());
                row = Component.text().append(color(settings.getString("prefix", "&8[&bЧат&8] &r")));
                inRow = 0;
            }
            if (inRow > 0) {
                row.append(Component.text(" "));
            }
            row.append(color(msg("mail-from")
                    .replace("{player}", mail.name)
                    .replace("{count}", String.valueOf(mail.count)))
                    .clickEvent(ClickEvent.callback(audience -> runOnPlayer(audience, clicker -> deliverOfflineMessages(clicker, senderId))))
                    .hoverEvent(HoverEvent.showText(color("&eОткрыть письма от &f" + mail.name))));
            inRow++;
        }
        if (inRow > 0) {
            player.sendMessage(row.build());
        }
        play(player, "sounds.private-message");
    }

    private void deliverOfflineMessages(Player player, String senderId) {
        String key = player.getUniqueId().toString();
        List<Map<?, ?>> list = offlineMessages.getMapList(key);
        if (list.isEmpty()) {
            send(player, msg("mail-empty"));
            return;
        }
        List<Map<?, ?>> remaining = new ArrayList<>();
        List<Map<?, ?>> selected = new ArrayList<>();
        for (Map<?, ?> map : list) {
            Object id = map.get("sender-id");
            if (id != null && senderId.equals(String.valueOf(id))) {
                selected.add(map);
            } else {
                remaining.add(map);
            }
        }
        if (selected.isEmpty()) {
            send(player, msg("mail-empty"));
            return;
        }
        send(player, msg("offline-header"));
        String lastSenderId = null;
        for (Map<?, ?> map : selected) {
            Object senderValue = map.get("sender-name");
            Object messageValue = map.get("message");
            Object idValue = map.get("sender-id");
            String senderName = senderValue == null ? "Игрок" : String.valueOf(senderValue);
            String message = messageValue == null ? "" : String.valueOf(messageValue);
            boolean allowColor = Boolean.TRUE.equals(map.get("color"));
            lastSenderId = idValue == null ? null : String.valueOf(idValue);
            player.sendMessage(Component.text()
                    .append(color("&d[ЛС] &7от &f"))
                    .append(Component.text(senderName, net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .append(color(" &8➜ "))
                    .append(playerText(message, allowColor))
                    .build());
        }
        offlineMessages.set(key, remaining.isEmpty() ? null : remaining);
        saveQuietly(offlineMessages, offlineMessagesFile);
        if (lastSenderId != null) {
            try {
                privateTargets.put(player.getUniqueId(), UUID.fromString(lastSenderId));
                replyTargets.put(player.getUniqueId(), UUID.fromString(lastSenderId));
            } catch (IllegalArgumentException ignored) {
            }
        }
        play(player, "sounds.private-message");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("local")) {
            return switchMode(sender, ChatMode.LOCAL);
        }
        if (name.equals("global")) {
            return switchMode(sender, ChatMode.GLOBAL);
        }
        if (name.equals("msg")) {
            return commandMsg(sender, args);
        }
        if (name.equals("reply")) {
            return commandReply(sender, args);
        }
        if (name.equals("ignore")) {
            return commandIgnore(sender, args, true);
        }
        if (name.equals("unignore")) {
            return commandIgnore(sender, args, false);
        }
        if (name.equals("mute") || name.equals("tempmute")) {
            return commandMute(sender, args, 0);
        }
        if (name.equals("unmute")) {
            return commandUnmute(sender, args, 0);
        }
        if (name.equals("discord") || name.equals("telegram") || name.equals("site")) {
            return commandLink(sender, name);
        }
        return commandVc(sender, args);
    }

    private boolean commandVc(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            for (String line : messages.getStringList("help")) {
                send(sender, line);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("vibechat.reload") && !sender.hasPermission("vibechat.admin")) {
                send(sender, msg("no-permission"));
                return true;
            }
            reloadEverything();
            hookIntegrations();
            send(sender, msg("reload"));
            return true;
        }
        if (sub.equals("mail")) {
            if (!(sender instanceof Player player)) {
                send(sender, msg("players-only"));
                return true;
            }
            if (args.length < 2) {
                showMailSummary(player);
                return true;
            }
            deliverOfflineMessages(player, args[1]);
            return true;
        }
        if (sub.equals("replyto")) {
            if (!(sender instanceof Player player)) {
                send(sender, msg("players-only"));
                return true;
            }
            if (args.length < 2) {
                return true;
            }
            selectPublicReply(player, args[1], args.length > 2 ? args[2] : "local");
            return true;
        }
        if (sub.equals("sound")) {
            if (!(sender instanceof Player player)) {
                send(sender, msg("players-only"));
                return true;
            }
            boolean enabled = args.length < 2 || args[1].equalsIgnoreCase("on");
            setSounds(player, enabled);
            send(player, enabled ? msg("sound-on") : msg("sound-off"));
            return true;
        }
        if (sub.equals("ignore")) {
            return commandIgnore(sender, Arrays.copyOfRange(args, 1, args.length), true);
        }
        if (sub.equals("unignore")) {
            return commandIgnore(sender, Arrays.copyOfRange(args, 1, args.length), false);
        }
        if (sub.equals("mute")) {
            return commandMute(sender, args, 1);
        }
        if (sub.equals("unmute")) {
            return commandUnmute(sender, args, 1);
        }
        if (sub.equals("invise") || sub.equals("invis") || sub.equals("vanish")) {
            return commandInvise(sender, args);
        }
        send(sender, "&cНеизвестная команда. /vc help");
        return true;
    }

    private boolean switchMode(CommandSender sender, ChatMode mode) {
        if (!(sender instanceof Player player)) {
            send(sender, msg("players-only"));
            return true;
        }
        modes.put(player.getUniqueId(), mode);
        privateTargets.remove(player.getUniqueId());
        publicReplyTargets.remove(player.getUniqueId());
        touch(player);
        send(player, mode == ChatMode.GLOBAL ? msg("chat-global") : msg("chat-local"));
        return true;
    }

    private void selectPublicReply(Player player, String uuidText, String modeText) {
        try {
            UUID targetId = UUID.fromString(uuidText);
            if (targetId.equals(player.getUniqueId())) {
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            String targetName = target.getName();
            if (targetName == null || targetName.isBlank()) {
                send(player, msg("not-found"));
                return;
            }
            ReplyClickMode clickMode = parseReplyClickMode(modeText);
            if (clickMode == ReplyClickMode.PRIVATE) {
                privateTargets.put(player.getUniqueId(), targetId);
                replyTargets.put(player.getUniqueId(), targetId);
                publicReplyTargets.remove(player.getUniqueId());
            } else {
                privateTargets.remove(player.getUniqueId());
                publicReplyTargets.put(player.getUniqueId(), new ReplySelection(targetId, clickMode == ReplyClickMode.GLOBAL ? ChatMode.GLOBAL : ChatMode.LOCAL));
            }
            touch(player);
            send(player, msg("public-reply-selected").replace("{player}", targetName));
        } catch (IllegalArgumentException ignored) {
            send(player, msg("not-found"));
        }
    }

    private ReplyClickMode parseReplyClickMode(String modeText) {
        try {
            return ReplyClickMode.valueOf(modeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ReplyClickMode.LOCAL;
        }
    }

    private boolean commandMsg(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, msg("players-only"));
            return true;
        }
        if (args.length < 2) {
            send(player, "&cИспользование: /t <ник> <сообщение>");
            return true;
        }
        OfflinePlayer target = findOffline(args[0]);
        if (target == null || target.getUniqueId() == null) {
            send(player, msg("not-found"));
            return true;
        }
        privateTargets.put(player.getUniqueId(), target.getUniqueId());
        touch(player);
        sendPrivate(player, target.getUniqueId(), join(args, 1));
        return true;
    }

    private boolean commandReply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, msg("players-only"));
            return true;
        }
        if (args.length == 0) {
            send(player, "&cИспользование: /r <сообщение>");
            return true;
        }
        UUID target = replyTargets.get(player.getUniqueId());
        if (target == null) {
            send(player, msg("not-found"));
            return true;
        }
        sendPrivate(player, target, join(args, 0));
        return true;
    }

    private boolean commandIgnore(CommandSender sender, String[] args, boolean add) {
        if (!(sender instanceof Player player)) {
            send(sender, msg("players-only"));
            return true;
        }
        if (args.length < 1) {
            send(player, add ? "&cИспользование: /ignore <ник>" : "&cИспользование: /unignore <ник>");
            return true;
        }
        OfflinePlayer target = findOffline(args[0]);
        if (target == null || target.getUniqueId() == null) {
            send(player, msg("not-found"));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, msg("cannot-self"));
            return true;
        }
        Set<String> list = new HashSet<>(ignores.getStringList(player.getUniqueId().toString()));
        if (add) {
            list.add(target.getUniqueId().toString());
        } else {
            list.remove(target.getUniqueId().toString());
        }
        ignores.set(player.getUniqueId().toString(), new ArrayList<>(list));
        saveQuietly(ignores, ignoresFile);
        send(player, (add ? msg("ignore-added") : msg("ignore-removed")).replace("{player}", target.getName() == null ? args[0] : target.getName()));
        return true;
    }

    private boolean commandMute(CommandSender sender, String[] args, int offset) {
        if (!sender.hasPermission("vibechat.mute") && !sender.hasPermission("vibechat.admin")) {
            send(sender, msg("no-permission"));
            return true;
        }
        if (args.length < offset + 2) {
            send(sender, offset == 0 ? "&cИспользование: /mute <ник> <время> [причина]" : "&cИспользование: /vc mute <ник> <время> [причина]");
            return true;
        }
        OfflinePlayer target = findOffline(args[offset]);
        if (target == null || target.getUniqueId() == null) {
            send(sender, msg("not-found"));
            return true;
        }
        long durationMillis = parseDurationMillis(args[offset + 1]);
        if (durationMillis <= 0L) {
            send(sender, "&cВремя: 10, 10m, 30s, 1h или 2d.");
            return true;
        }
        mutes.set(target.getUniqueId().toString(), System.currentTimeMillis() + durationMillis);
        saveQuietly(mutes, mutesFile);
        send(sender, msg("mute-given")
                .replace("{player}", target.getName() == null ? args[offset] : target.getName())
                .replace("{time}", formatDuration(durationMillis))
                .replace("{minutes}", String.valueOf(Math.max(1L, durationMillis / 60_000L))));
        return true;
    }

    private boolean commandUnmute(CommandSender sender, String[] args, int offset) {
        if (!sender.hasPermission("vibechat.unmute") && !sender.hasPermission("vibechat.admin")) {
            send(sender, msg("no-permission"));
            return true;
        }
        if (args.length < offset + 1) {
            send(sender, offset == 0 ? "&cИспользование: /unmute <ник>" : "&cИспользование: /vc unmute <ник>");
            return true;
        }
        OfflinePlayer target = findOffline(args[offset]);
        if (target == null || target.getUniqueId() == null) {
            send(sender, msg("not-found"));
            return true;
        }
        mutes.set(target.getUniqueId().toString(), null);
        saveQuietly(mutes, mutesFile);
        send(sender, msg("unmuted").replace("{player}", target.getName() == null ? args[offset] : target.getName()));
        return true;
    }

    private long parseDurationMillis(String input) {
        if (input == null || input.isBlank()) {
            return -1L;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("^(\\d+)([smhd]?)$").matcher(value);
        if (!matcher.matches()) {
            return -1L;
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1L;
        }
        if (amount <= 0L) {
            return -1L;
        }
        String unit = matcher.group(2);
        long multiplier = switch (unit) {
            case "s" -> 1_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            default -> 60_000L;
        };
        if (amount > Long.MAX_VALUE / multiplier) {
            return -1L;
        }
        return amount * multiplier;
    }

    private boolean commandInvise(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, msg("players-only"));
            return true;
        }
        if (!player.hasPermission("vibechat.invise") && !player.hasPermission("vibechat.admin")) {
            send(player, msg("no-permission"));
            return true;
        }
        boolean enable = args.length < 2 || args[1].equalsIgnoreCase("on");
        preferences.set(player.getUniqueId() + ".invise", enable);
        saveQuietly(preferences, preferencesFile);
        applyInvise(player, enable);
        send(player, enable ? msg("invise-on") : msg("invise-off"));
        return true;
    }

    private boolean commandLink(CommandSender sender, String name) {
        String url = settings.getString("links." + name, "");
        if (url == null || url.isBlank()) {
            send(sender, msg(name + "-empty"));
            return true;
        }
        Component component = color(settings.getString("prefix", "&8[&bЧат&8] &r"))
                .append(Component.text(url)
                        .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(safeUrl(url)))
                        .hoverEvent(HoverEvent.showText(color("&eОткрыть ссылку"))));
        sender.sendMessage(component);
        return true;
    }

    private String safeUrl(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return new java.net.URI("https://" + url).toString();
            }
            return new java.net.URI(url).toString();
        } catch (URISyntaxException e) {
            return "https://example.com";
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("vibechat")) {
            if (args.length == 1) {
                return partial(args[0], "help", "reload", "sound", "ignore", "unignore", "mute", "unmute", "invise");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("sound")) {
                return partial(args[1], "on", "off");
            }
            if (args.length == 2 && Set.of("ignore", "unignore", "mute", "unmute").contains(args[0].toLowerCase(Locale.ROOT))) {
                return onlineNames(args[1]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("invise")) {
                return partial(args[1], "on", "off");
            }
        }
        if (name.equals("msg") && args.length == 1) {
            return onlineNames(args[0]);
        }
        if ((name.equals("ignore") || name.equals("unignore")) && args.length == 1) {
            return onlineNames(args[0]);
        }
        return Collections.emptyList();
    }

    private List<String> partial(String input, String... values) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private List<String> onlineNames(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(player.getName());
            }
        }
        return result;
    }

    private void applyInvise(Player player, boolean enable) {
        if (!settings.getBoolean("features.experimental-invise", true)) {
            return;
        }
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(TEAM_INVISE);
        if (team == null) {
            team = scoreboard.registerNewTeam(TEAM_INVISE);
            team.setNameTagVisibility(NameTagVisibility.NEVER);
            team.setCanSeeFriendlyInvisibles(true);
        }
        if (enable) {
            if (!team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
            player.playerListName(Component.text(" "));
        } else {
            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
            player.playerListName(Component.text(player.getName()));
        }
    }

    private boolean isInvise(UUID uuid) {
        return preferences.getBoolean(uuid + ".invise", false);
    }

    private ChatMode mode(Player player) {
        return modes.getOrDefault(player.getUniqueId(), ChatMode.LOCAL);
    }

    private boolean isMuted(Player player) {
        if (player.hasPermission("vibechat.bypass.mute") || player.hasPermission("vibechat.bypass.all")) {
            return false;
        }
        long until = mutes.getLong(player.getUniqueId().toString(), 0L);
        if (until <= 0L) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            mutes.set(player.getUniqueId().toString(), null);
            saveQuietly(mutes, mutesFile);
            return false;
        }
        return true;
    }

    private String formatMuteTime(Player player) {
        long until = mutes.getLong(player.getUniqueId().toString(), 0L);
        long seconds = Math.max(1, (until - System.currentTimeMillis()) / 1000L);
        return formatDuration(seconds * 1000L);
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(1L, millis / 1000L);
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        List<String> parts = new ArrayList<>();
        if (days > 0L) {
            parts.add(days + " д.");
        }
        if (hours > 0L) {
            parts.add(hours + " ч.");
        }
        if (minutes > 0L) {
            parts.add(minutes + " мин.");
        }
        if (parts.isEmpty() || seconds > 0L) {
            parts.add(seconds + " сек.");
        }
        return String.join(" ", parts);
    }

    private boolean isIgnoring(UUID owner, UUID target) {
        return ignores.getStringList(owner.toString()).contains(target.toString());
    }

    private boolean hasBypass(Player player, String type) {
        return player.hasPermission("vibechat.bypass.all") || player.hasPermission("vibechat.bypass." + type);
    }

    private void loadPreferences(Player player) {
        if (!preferences.contains(player.getUniqueId() + ".sounds")) {
            preferences.set(player.getUniqueId() + ".sounds", settings.getBoolean("sounds.enabled-default", true));
            saveQuietly(preferences, preferencesFile);
        }
    }

    private boolean soundsEnabled(Player player) {
        return preferences.getBoolean(player.getUniqueId() + ".sounds", settings.getBoolean("sounds.enabled-default", true));
    }

    private void setSounds(Player player, boolean enabled) {
        preferences.set(player.getUniqueId() + ".sounds", enabled);
        saveQuietly(preferences, preferencesFile);
    }

    private void play(Player player, String path) {
        if (player == null || !soundsEnabled(player)) {
            return;
        }
        String soundName = settings.getString(path, "");
        if (soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound. valueOf(soundName), 0.65f, 1.0f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void runOnPlayer(Audience audience, java.util.function.Consumer<Player> action) {
        if (!(audience instanceof Player player)) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> action.accept(player));
    }

    private String prefix(Player player) {
        return vault(player, true);
    }

    private String suffix(Player player) {
        return vault(player, false);
    }

    private String vault(Player player, boolean prefix) {
        if (vaultChat == null) {
            return "";
        }
        try {
            Method method = prefix ? vaultPrefixPlayer : vaultSuffixPlayer;
            if (method != null) {
                return Objects.toString(method.invoke(vaultChat, player), "");
            }
            Method worldMethod = prefix ? vaultPrefixWorldPlayer : vaultSuffixWorldPlayer;
            if (worldMethod != null) {
                return Objects.toString(worldMethod.invoke(vaultChat, player.getWorld().getName(), player), "");
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String applyPlaceholders(Player player, String text) {
        if (papiSetPlaceholders == null) {
            return text;
        }
        try {
            return Objects.toString(papiSetPlaceholders.invoke(null, player, text), text);
        } catch (Throwable ignored) {
            return text;
        }
    }

    private OfflinePlayer findOffline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) {
                return offline;
            }
        }
        return Bukkit.getOfflinePlayer(name);
    }

    private String msg(String path) {
        return messages.getString(path, "&cСообщение не найдено: " + path);
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(color(settings.getString("prefix", "&8[&bЧат&8] &r") + message));
    }

    private Component color(String message) {
        return LEGACY.deserialize(message == null ? "" : message);
    }

    private boolean canUseColor(Player player) {
        return player.hasPermission("vibechat.color");
    }

    private String stripColorCodes(String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        String previous;
        do {
            previous = result;
            result = LEGACY_CODE_PATTERN.matcher(result).replaceAll("");
        } while (!result.equals(previous));
        return result;
    }

    private String prepareMessage(Player player, String message) {
        String plain = stripColorCodes(message).trim();
        String filtered = filterBadWords(plain);
        if (canUseColor(player) && filtered.equals(plain)) {
            return filterBadWords(message);
        }
        return filtered;
    }

    private Component playerText(String text, boolean allowColor) {
        if (allowColor) {
            return color("&f" + text);
        }
        return Component.text(text, net.kyori.adventure.text.format.NamedTextColor.WHITE);
    }

    private String join(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private String normalize(String input) {
        return ChatColor.stripColor(input).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean containsWord(String message, String word) {
        return Pattern.compile("(?iu)(^|\\W)" + Pattern.quote(word) + "($|\\W)").matcher(message).find();
    }

    private void saveQuietly(FileConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException e) {
            getLogger().warning("Cannot save " + file.getName() + ": " + e.getMessage());
        }
    }

    private enum ChatMode {
        LOCAL,
        GLOBAL
    }

    private enum ReplyClickMode {
        NONE,
        LOCAL,
        GLOBAL,
        PRIVATE
    }

    private static final class MailSender {
        private final String name;
        private int count;

        private MailSender(String name) {
            this.name = name;
        }
    }

    private static final class PublicReply {
        private final UUID id;
        private final String name;

        private PublicReply(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class ReplySelection {
        private final UUID targetId;
        private final ChatMode mode;

        private ReplySelection(UUID targetId, ChatMode mode) {
            this.targetId = targetId;
            this.mode = mode;
        }
    }
}
