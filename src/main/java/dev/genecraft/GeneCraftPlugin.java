package dev.genecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.persistence.PersistentDataType;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import io.papermc.paper.datacomponent.item.ResolvableProfile;

public final class GeneCraftPlugin extends JavaPlugin implements CommandExecutor, Listener {
    private static final URI BRIDGE = URI.create("http://127.0.0.1:8765");
    private static final double PLAYER_ACTION_LIMIT = 32.0;
    private static final double WAYPOINT_ACTION_LIMIT = 64.0;
    private static final double GUARD_KILL_RADIUS = 12.0;
    private static final double GUARD_ALERT_RADIUS = 18.0;
    private static final int WOOD_SEARCH_RADIUS = 24;
    private static final long WOOD_RESCAN_DELAY_MS = 3000L;
    private static final String PERSISTENT_NAMESPACE = "fableorbit";

    private NamespacedKey agentNameKey;
    private NamespacedKey guardOwnerKey;
    private NamespacedKey agentOwnerKey;
    private NamespacedKey agentSkinKey;
    private NamespacedKey agentFormKey;
    private HttpClient http;
    private final Set<UUID> pendingPlans = ConcurrentHashMap.newKeySet();
    private final Set<UUID> invalidatedPlans = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> followTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> pendingInboxSnapshots = new ConcurrentHashMap<>();
    private int guardPulse = 0;

    @Override
    public void onEnable() {
        // Keep the old persistent-data namespace so villagers from the previous
        // older agent entities remain identifiable after the rebrand.
        agentNameKey = new NamespacedKey(PERSISTENT_NAMESPACE, "agent_name");
        guardOwnerKey = new NamespacedKey(PERSISTENT_NAMESPACE, "bodyguard_owner");
        agentOwnerKey = new NamespacedKey(PERSISTENT_NAMESPACE, "agent_owner");
        agentSkinKey = new NamespacedKey(PERSISTENT_NAMESPACE, "agent_skin");
        agentFormKey = new NamespacedKey(PERSISTENT_NAMESPACE, "agent_form");
        migrateLegacyConfig();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        Objects.requireNonNull(getCommand("genecraft"), "genecraft command missing from plugin.yml")
                .setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickBodyguards, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickAutonomousAgents, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickJobs, 20L, 10L);
        Bukkit.getScheduler().runTask(this, this::relabelExistingAgents);
        getLogger().info("GeneCraft ready. Use /genecraft help to start.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (Mob agent : ownedAgents(player)) {
            if (!activeJob(agent)) continue;
            agent.getPathfinder().stopPathfinding();
            changeJobStatus(null, agent, "waiting_for_owner", "");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("spawn")) {
            return spawn(sender, args);
        }
        if (subcommand.equals("waypoint")) {
            return waypoint(sender, args);
        }
        if (subcommand.equals("demo")) {
            return demo(sender, args);
        }
        if (subcommand.equals("ask")) {
            return ask(sender, args);
        }
        if (subcommand.equals("stop")) {
            return stop(sender, args);
        }
        if (subcommand.equals("rename")) {
            return renameAgent(sender, args);
        }
        if (subcommand.equals("bodyguard")) {
            return bodyguard(sender, args);
        }
        if (subcommand.equals("goal")) {
            return goal(sender, args);
        }
        if (subcommand.equals("autonomy")) {
            return autonomy(sender, args);
        }
        if (subcommand.equals("job")) {
            return jobCommand(sender, args);
        }
        if (subcommand.equals("supplies")) {
            return supplies(sender);
        }
        if (subcommand.equals("inbox")) {
            return inbox(sender, args);
        }
        if (subcommand.equals("remove")) {
            return removeAgent(sender, args);
        }
        if (subcommand.equals("login")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Use /genecraft login in Minecraft to review the optional ChatGPT plan connection.");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("continue")) {
                bridgePost(sender, "/auth/login", new JsonObject(), "Opening the official OpenAI sign-in page…");
            } else {
                showChatGptLoginPrompt(sender);
            }
            return true;
        }
        if (subcommand.equals("logout")) {
            getConfig().set("chatgptPlanWelcomeShown", false);
            saveConfig();
            bridgePost(sender, "/auth/logout", new JsonObject(), "Signing out of the connected ChatGPT account…");
            return true;
        }
        if (subcommand.equals("status")) {
            bridgeGet(sender, "/health", "Checking the local agent bridge…");
            return true;
        }
        if (subcommand.equals("models")) {
            bridgeGet(sender, "/models", "Loading models available to this account…");
            return true;
        }
        if (subcommand.equals("model")) {
            if (args.length < 2) {
                sender.sendMessage("Use /genecraft model <model-id>. Run /genecraft models to see available models.");
                return true;
            }
            JsonObject body = new JsonObject();
            body.addProperty("model", args[1]);
            bridgePost(sender, "/settings/model", body, "Checking that model for your account…");
            return true;
        }
        help(sender);
        return true;
    }

    private boolean renameAgent(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("Use /genecraft rename <old-name> <new-name>");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§b[GeneCraft] §fI can't find that agent nearby.");
            return true;
        }
        String name = clean(args[2], 24);
        if (!isAgentName(name)) {
            player.sendMessage("Use a name with 1-24 letters, numbers, underscores, or hyphens.");
            return true;
        }
        if (ownedAgents(player).stream().anyMatch(existing -> !existing.equals(agent) && agentName(existing).equalsIgnoreCase(name))) {
            player.sendMessage("§b[GeneCraft] §fYou already have an agent with that name.");
            return true;
        }
        agent.getPersistentDataContainer().set(agentNameKey, PersistentDataType.STRING, name);
        updateAgentLabel(agent, name);
        player.sendMessage("§b[GeneCraft] §fRenamed that agent to " + name + ".");
        return true;
    }

    private boolean bodyguard(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "on";
        String key = "bodyguards." + player.getUniqueId() + ".enabled";
        if (mode.equals("status")) {
            boolean enabled = getConfig().getBoolean(key, false);
            player.sendMessage("§b[GeneCraft] §fBodyguard Bill is " + (enabled ? "on duty." : "off duty."));
            return true;
        }
        if (mode.equals("off")) {
            getConfig().set(key, false);
            saveConfig();
            removeBodyguards(player.getUniqueId());
            player.sendMessage("§b[GeneCraft] §fBodyguard Bill is off duty. Use /genecraft bodyguard to call him back.");
            return true;
        }
        if (!mode.equals("on")) {
            player.sendMessage("Use /genecraft bodyguard [on|off|status]");
            return true;
        }

        getConfig().set(key, true);
        saveConfig();
        IronGolem guard = findBodyguard(player.getUniqueId(), player.getWorld());
        if (guard == null) guard = spawnBodyguard(player);
        outfitBodyguard(guard);
        player.sendMessage("§b[GeneCraft] §fBodyguard Bill is on duty. He follows you and neutralizes hostile mobs nearby without GPT calls. Use /genecraft bodyguard off to dismiss him.");
        return true;
    }

    private boolean goal(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("Use /genecraft goal <agent> set <goal>, clear, or status");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§b[GeneCraft] §fI can't find that agent nearby.");
            return true;
        }
        String key = statePath(agent) + ".goal";
        String operation = args[2].toLowerCase(Locale.ROOT);
        if (operation.equals("clear")) {
            getConfig().set(key, null);
            saveConfig();
            player.sendMessage("§b[GeneCraft] §fCleared " + agentName(agent) + "'s saved goal.");
        } else if (operation.equals("status")) {
            String current = getConfig().getString(key, "");
            player.sendMessage("§b[GeneCraft] §f" + agentName(agent) + "'s goal: " + (current.isBlank() ? "none" : clean(current, 240)));
        } else if (operation.equals("set") && args.length >= 4) {
            String text = clean(String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)), 240).trim();
            if (text.isBlank()) {
                player.sendMessage("§b[GeneCraft] §fA goal needs some text.");
                return true;
            }
            getConfig().set(key, text);
            saveConfig();
            player.sendMessage("§b[GeneCraft] §fSaved " + agentName(agent) + "'s goal: " + text);
        } else {
            player.sendMessage("Use /genecraft goal " + agentName(agent) + " set <goal>, clear, or status");
        }
        return true;
    }

    private boolean autonomy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("Use /genecraft autonomy <agent> on [seconds], off, or status");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§b[GeneCraft] §fI can't find that agent nearby.");
            return true;
        }
        String path = statePath(agent) + ".autonomy";
        String operation = args[2].toLowerCase(Locale.ROOT);
        if (operation.equals("status")) {
            boolean enabled = getConfig().getBoolean(path + ".enabled", false);
            int interval = getConfig().getInt(path + ".intervalSeconds", 90);
            player.sendMessage("§b[GeneCraft] §f" + agentName(agent) + "'s observation loop is "
                    + (enabled ? "on" : "off") + (enabled ? " (every " + interval + " seconds)." : "."));
            return true;
        }
        if (operation.equals("off")) {
            getConfig().set(path + ".enabled", false);
            saveConfig();
            player.sendMessage("§b[GeneCraft] §f" + agentName(agent) + "'s observation loop is off.");
            return true;
        }
        if (!operation.equals("on")) {
            player.sendMessage("Use /genecraft autonomy <agent> on [seconds], off, or status");
            return true;
        }
        String goal = getConfig().getString(statePath(agent) + ".goal", "");
        if (goal.isBlank()) {
            player.sendMessage("§b[GeneCraft] §fGive " + agentName(agent) + " a persistent goal first with /genecraft goal " + agentName(agent) + " set <goal>.");
            return true;
        }
        int active = (int) ownedAgents(player).stream()
                .filter(candidate -> getConfig().getBoolean(statePath(candidate) + ".autonomy.enabled", false))
                .filter(candidate -> !candidate.getUniqueId().equals(agent.getUniqueId()))
                .count();
        if (!getConfig().getBoolean(path + ".enabled", false) && active >= 2) {
            player.sendMessage("§b[GeneCraft] §fFor usage control, only two agents can observe autonomously at once.");
            return true;
        }
        int interval = 90;
        if (args.length >= 4) {
            try {
                interval = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {
                player.sendMessage("Choose an interval from 30 to 1800 seconds.");
                return true;
            }
        }
        if (interval < 30 || interval > 1800) {
            player.sendMessage("Choose an interval from 30 to 1800 seconds.");
            return true;
        }
        getConfig().set(path + ".enabled", true);
        getConfig().set(path + ".intervalSeconds", interval);
        getConfig().set(path + ".nextRunAt", System.currentTimeMillis() + interval * 1000L);
        saveConfig();
        player.sendMessage("§b[GeneCraft] §f" + agentName(agent) + " will check the nearby world every " + interval
                + " seconds while you are online and within 32 blocks. Use /genecraft autonomy " + agentName(agent) + " off to stop it.");
        return true;
    }

    private boolean inbox(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Use /genecraft inbox <agent>");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§b[GeneCraft] §fI can't find that agent nearby.");
            return true;
        }
        List<Map<String, Object>> messages = readInbox(agent);
        if (messages.isEmpty()) {
            player.sendMessage("§b[GeneCraft] §f" + agentName(agent) + " has no waiting messages.");
        } else {
            for (Map<String, Object> message : messages) {
                player.sendMessage("§b[" + clean(String.valueOf(message.get("from")), 24) + " → "
                        + agentName(agent) + "] §f" + clean(String.valueOf(message.get("text")), 120));
            }
        }
        return true;
    }

    private void migrateLegacyConfig() {
        Path newConfig = getDataFolder().toPath().resolve("config.yml");
        if (Files.exists(newConfig)) return;
        for (String oldName : List.of("GenesisOrbit", "FableOrbit")) {
            Path oldConfig = Bukkit.getPluginsFolder().toPath().resolve(oldName).resolve("config.yml");
            if (!Files.isRegularFile(oldConfig)) continue;
            try {
                Files.createDirectories(newConfig.getParent());
                Files.copy(oldConfig, newConfig);
                getLogger().info("Migrated saved settings and waypoints from the earlier GeneCraft prototype.");
                return;
            } catch (Exception error) {
                getLogger().warning("Could not migrate the previous settings file: " + error.getMessage());
            }
        }
    }

    private void relabelExistingAgents() {
        for (World world : Bukkit.getWorlds()) {
            relabelAgentsInWorld(world);
        }
    }

    private void relabelAgentsInWorld(World world) {
        for (Mob agent : world.getEntitiesByClass(Mob.class)) {
            if (!agent.getPersistentDataContainer().has(agentNameKey, PersistentDataType.STRING)) continue;
            String name = agentName(agent);
            if (name.equalsIgnoreCase("Ada")) {
                name = "Gen";
                agent.getPersistentDataContainer().set(agentNameKey, PersistentDataType.STRING, name);
            }
            if ("player".equals(agent.getPersistentDataContainer().get(agentFormKey, PersistentDataType.STRING))) {
                agent.setInvisible(true);
                attachPlayerAvatar(agent, name, agent.getPersistentDataContainer().get(agentSkinKey, PersistentDataType.STRING));
            } else {
                agent.setCustomName("§d" + name + " §7[GeneCraft]");
                agent.setCustomNameVisible(true);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> relabelAgentsInWorld(player.getWorld()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAddressedAgentChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (!message.startsWith("@")) return;
        int split = message.indexOf(' ');
        if (split < 2 || split == message.length() - 1) return;
        String targetName = message.substring(1, split).replaceAll("[:,]$", "");
        if (!isAgentName(targetName)) return;
        String prompt = message.substring(split + 1).trim();
        if (prompt.isBlank()) return;
        event.setCancelled(true);
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            Mob agent = findAgent(player, targetName);
            if (agent == null) {
                player.sendMessage("§d[GeneCraft] §fI can't find your agent '" + clean(targetName, 24) + "' nearby.");
                return;
            }
            if (isDirectCancel(prompt)) {
                invalidatePendingPlan(agent);
                cancelFollow(agent.getUniqueId());
                if (activeJob(agent)) cancelJob(player, agent, true);
                else {
                    agent.getPathfinder().stopPathfinding();
                    agentSay(player, agentName(agent), "Stopped. I don’t have an active work order now.");
                }
                return;
            }
            player.sendMessage(Component.text("You → " + agentName(agent) + ": " + clean(prompt, 240), NamedTextColor.GRAY));
            requestPlan(player, agent, prompt, false);
        });
    }

    private boolean bodyguardEnabled(UUID ownerId) {
        return getConfig().getBoolean("bodyguards." + ownerId + ".enabled", false);
    }

    private void tickBodyguards() {
        guardPulse++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!bodyguardEnabled(player.getUniqueId())) continue;
            IronGolem guard = findBodyguard(player.getUniqueId(), player.getWorld());
            if (guard == null) guard = spawnBodyguard(player);
            outfitBodyguard(guard);

            double distanceSquared = guard.getLocation().distanceSquared(player.getLocation());
            if (distanceSquared > 24.0 * 24.0) {
                guard.getPathfinder().stopPathfinding();
                guard.teleport(player.getLocation().clone().add(1.5, 0, 1.5));
            } else if (guardPulse % 2 == 0) {
                if (distanceSquared > 3.0 * 3.0) {
                    guard.getPathfinder().moveTo(player.getLocation(), 1.25);
                } else if (guard.getTarget() == null) {
                    guard.getPathfinder().stopPathfinding();
                }
            }

            neutralizeNearbyEnemies(player, guard);
        }
    }

    private IronGolem findBodyguard(UUID ownerId, World ownerWorld) {
        IronGolem found = null;
        for (World world : Bukkit.getWorlds()) {
            for (IronGolem golem : world.getEntitiesByClass(IronGolem.class)) {
                String storedOwner = golem.getPersistentDataContainer().get(guardOwnerKey, PersistentDataType.STRING);
                if (!ownerId.toString().equals(storedOwner)) continue;
                if (world.equals(ownerWorld) && found == null) {
                    found = golem;
                } else {
                    golem.remove();
                }
            }
        }
        return found;
    }

    private void removeBodyguards(UUID ownerId) {
        for (World world : Bukkit.getWorlds()) {
            for (IronGolem golem : world.getEntitiesByClass(IronGolem.class)) {
                String storedOwner = golem.getPersistentDataContainer().get(guardOwnerKey, PersistentDataType.STRING);
                if (ownerId.toString().equals(storedOwner)) golem.remove();
            }
        }
    }

    private IronGolem spawnBodyguard(Player owner) {
        Location at = owner.getLocation().clone().add(1.5, 0, 1.5);
        IronGolem guard = owner.getWorld().spawn(at, IronGolem.class);
        guard.getPersistentDataContainer().set(guardOwnerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        guard.setPlayerCreated(true);
        guard.setPersistent(true);
        outfitBodyguard(guard);
        return guard;
    }

    private void outfitBodyguard(IronGolem guard) {
        guard.setCustomName("§b⚙ Bodyguard Bill §8[GeneCraft]");
        guard.setCustomNameVisible(true);
        guard.setInvulnerable(true);
        guard.setPersistent(true);
        guard.setAware(true);
        if (!guard.hasPotionEffect(PotionEffectType.GLOWING)) {
            guard.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, true, false, false));
        }
    }

    private void neutralizeNearbyEnemies(Player owner, IronGolem guard) {
        List<Enemy> enemies = owner.getNearbyEntities(GUARD_ALERT_RADIUS, GUARD_ALERT_RADIUS, GUARD_ALERT_RADIUS).stream()
                .filter(Enemy.class::isInstance)
                .map(Enemy.class::cast)
                .toList();
        Enemy nearest = enemies.stream()
                .min(Comparator.comparingDouble(enemy -> enemy.getLocation().distanceSquared(owner.getLocation())))
                .orElse(null);
        if (nearest instanceof LivingEntity target && guard.getTarget() != target) guard.setTarget(target);
        if (nearest == null && guard.getTarget() instanceof Enemy) guard.setTarget(null);

        int eliminated = 0;
        for (Enemy enemy : enemies) {
            if (enemy.getLocation().distanceSquared(owner.getLocation()) > GUARD_KILL_RADIUS * GUARD_KILL_RADIUS) continue;
            enemy.setNoDamageTicks(0);
            enemy.damage(1_000_000.0, guard);
            if (enemy.isValid() && enemy.getHealth() > 0.0) enemy.remove();
            eliminated++;
        }
        if (eliminated > 0 && owner.isOnline()) {
            owner.sendActionBar(Component.text("Bodyguard Bill neutralized " + eliminated + " hostile mob" + (eliminated == 1 ? "" : "s"), NamedTextColor.AQUA));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectGuardOwner(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player owner) || !bodyguardEnabled(owner.getUniqueId())) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Entity shooter) attacker = shooter;
        }
        if (attacker instanceof Enemy) event.setCancelled(true);
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Use /genecraft spawn <name> [villager|allay|cat|wolf|fox] or /genecraft spawn <name> skin <player-name>");
            return true;
        }
        String name = clean(args[1], 24);
        if (!isAgentName(name)) {
            player.sendMessage("Use a name with 1-24 letters, numbers, underscores, or hyphens.");
            return true;
        }
        if (ownedAgents(player).stream().anyMatch(existing -> agentName(existing).equalsIgnoreCase(name))) {
            player.sendMessage("§b[GeneCraft] §fYou already have an agent with that name.");
            return true;
        }
        String form = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "villager";
        String skinName = null;
        if (form.equals("skin")) {
            if (args.length < 4 || !args[3].matches("[A-Za-z0-9_]{3,16}")) {
                player.sendMessage("Use /genecraft spawn <name> skin <Minecraft-player-name>.");
                return true;
            }
            skinName = args[3];
            form = "player";
        }
        EntityType type = switch (form) {
            case "villager", "player" -> EntityType.VILLAGER;
            case "allay" -> EntityType.ALLAY;
            case "cat" -> EntityType.CAT;
            case "wolf" -> EntityType.WOLF;
            case "fox" -> EntityType.FOX;
            default -> null;
        };
        if (type == null) {
            player.sendMessage("Choose a form: villager, allay, cat, wolf, fox, or skin <Minecraft-player-name>.");
            return true;
        }
        if (ownedAgents(player).size() >= 8) {
            player.sendMessage("§b[GeneCraft] §fThis demo supports up to eight agents per player.");
            return true;
        }
        Location spawnAt = player.getLocation().add(player.getLocation().getDirection().normalize().multiply(2.0));
        Entity spawned = player.getWorld().spawnEntity(spawnAt, type);
        if (!(spawned instanceof Mob agent)) {
            spawned.remove();
            player.sendMessage("§b[GeneCraft] §fThat form cannot be used as a moving agent.");
            return true;
        }
        if (agent instanceof Villager villager) villager.setProfession(Villager.Profession.LIBRARIAN);
        if (agent instanceof Wolf wolf) {
            wolf.setTamed(true);
            wolf.setOwner(player);
            wolf.setCollarColor(org.bukkit.DyeColor.PURPLE);
        }
        if (agent instanceof Cat cat) {
            cat.setTamed(true);
            cat.setOwner(player);
        }
        agent.setPersistent(true);
        agent.getPersistentDataContainer().set(agentNameKey, PersistentDataType.STRING, name);
        agent.getPersistentDataContainer().set(agentOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        agent.getPersistentDataContainer().set(agentFormKey, PersistentDataType.STRING, form);
        if (skinName != null) {
            agent.setInvisible(true);
            agent.setSilent(true);
            agent.setCustomNameVisible(false);
            agent.getPersistentDataContainer().set(agentSkinKey, PersistentDataType.STRING, skinName);
            if (!attachPlayerAvatar(agent, name, skinName)) {
                fallbackPlayerAvatar(agent, name);
                player.sendMessage("§b[GeneCraft] §fThe player-style display could not attach here; I spawned the standard villager form instead.");
            }
        } else {
            agent.setCustomName("§d" + name + " §7[GeneCraft]");
            agent.setCustomNameVisible(true);
        }
        saveConfig();
        player.sendMessage("§b[GeneCraft] §f" + name + " is ready as " + (skinName == null ? form : "a player-style avatar using " + skinName + "'s skin")
                + ". Try /genecraft ask " + name + " say hello.");
        return true;
    }

    private boolean attachPlayerAvatar(Mob controller, String name, String skinName) {
        if (skinName == null || skinName.isBlank()) return false;
        Mannequin avatar = controller.getPassengers().stream()
                .filter(Mannequin.class::isInstance).map(Mannequin.class::cast).findFirst().orElse(null);
        if (avatar == null) {
            avatar = controller.getWorld().spawn(controller.getLocation(), Mannequin.class);
            avatar.setPersistent(true);
            avatar.setInvulnerable(true);
            avatar.setImmovable(false);
            avatar.setCustomName("§d" + name + " §7[GeneCraft]");
            avatar.setCustomNameVisible(true);
            if (!controller.addPassenger(avatar)) {
                avatar.remove();
                return false;
            }
        } else {
            avatar.setCustomName("§d" + name + " §7[GeneCraft]");
            avatar.setCustomNameVisible(true);
        }
        UUID controllerId = controller.getUniqueId();
        PlayerProfile profile;
        try {
            profile = Bukkit.createProfile(skinName);
        } catch (IllegalArgumentException error) {
            controller.removePassenger(avatar);
            avatar.remove();
            return false;
        }
        profile.update().whenComplete((updated, error) -> Bukkit.getScheduler().runTask(this, () -> {
            Entity current = Bukkit.getEntity(controllerId);
            if (!(current instanceof Mob liveController) || !liveController.isValid()) return;
            if (error != null || updated == null) {
                getLogger().warning("Could not fetch the requested Minecraft skin for " + name + "; using a villager instead.");
                fallbackPlayerAvatar(liveController, name);
                return;
            }
            Mannequin liveAvatar = liveController.getPassengers().stream()
                    .filter(Mannequin.class::isInstance).map(Mannequin.class::cast).findFirst().orElse(null);
            if (liveAvatar == null) {
                getLogger().warning("Player-style display for " + name + " was removed before the skin loaded; using a villager instead.");
                fallbackPlayerAvatar(liveController, name);
                return;
            }
            liveAvatar.setProfile(ResolvableProfile.resolvableProfile(updated));
        }));
        return true;
    }

    private void fallbackPlayerAvatar(Mob controller, String name) {
        for (Entity passenger : List.copyOf(controller.getPassengers())) {
            if (passenger instanceof Mannequin) passenger.remove();
        }
        controller.setInvisible(false);
        controller.setSilent(false);
        controller.setCustomName("§d" + name + " §7[GeneCraft]");
        controller.setCustomNameVisible(true);
        controller.getPersistentDataContainer().set(agentFormKey, PersistentDataType.STRING, "villager");
        controller.getPersistentDataContainer().remove(agentSkinKey);
        String ownerId = controller.getPersistentDataContainer().get(agentOwnerKey, PersistentDataType.STRING);
        try {
            Player owner = ownerId == null ? null : Bukkit.getPlayer(UUID.fromString(ownerId));
            if (owner != null) owner.sendMessage("§b[GeneCraft] §fSkin lookup failed; " + name + " is using the villager form.");
        } catch (IllegalArgumentException ignored) {
            // An invalid or legacy owner field must not break the visible fallback.
        }
    }

    private void updateAgentLabel(Mob agent, String name) {
        if ("player".equals(agent.getPersistentDataContainer().get(agentFormKey, PersistentDataType.STRING))) {
            agent.getPassengers().stream().filter(Mannequin.class::isInstance).map(Mannequin.class::cast)
                    .forEach(avatar -> {
                        avatar.setCustomName("§d" + name + " §7[GeneCraft]");
                        avatar.setCustomNameVisible(true);
                    });
        } else {
            agent.setCustomName("§d" + name + " §7[GeneCraft]");
            agent.setCustomNameVisible(true);
        }
    }

    private boolean waypoint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            var section = getConfig().getConfigurationSection("waypoints");
            if (section == null || section.getKeys(false).isEmpty()) {
                player.sendMessage("§d[GeneCraft] §fNo waypoints saved yet. Stand somewhere and use /genecraft waypoint set <name>.");
                return true;
            }
            player.sendMessage("§d[GeneCraft] §fWaypoints: " + String.join(", ", section.getKeys(false)));
            return true;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("set")) {
            player.sendMessage("Use /genecraft waypoint set <name> or /genecraft waypoint list");
            return true;
        }
        String suppliedName = args[2];
        if (!isWaypointName(suppliedName)) {
            player.sendMessage("Use a waypoint name containing only letters, numbers, underscores, or hyphens (up to 24 characters).");
            return true;
        }
        String name = suppliedName.toLowerCase(Locale.ROOT);
        Location at = player.getLocation();
        String key = "waypoints." + name;
        getConfig().set(key + ".world", at.getWorld().getName());
        getConfig().set(key + ".x", at.getX());
        getConfig().set(key + ".y", at.getY());
        getConfig().set(key + ".z", at.getZ());
        saveConfig();
        player.sendMessage("§d[GeneCraft] §fSaved waypoint '" + name + "' at your current position.");
        return true;
    }

    private boolean demo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Use /genecraft demo <agent-name>. Spawn an agent first with /genecraft spawn <name>.");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§d[GeneCraft] §fI can't find that agent nearby. Spawn it with /genecraft spawn " + clean(args[1], 24) + ".");
            return true;
        }
        String name = agentName(agent);
        cancelFollow(agent.getUniqueId());
        player.sendMessage("§d[GeneCraft] §fRunning the model-free pathfinding demo with " + name + ".");
        agentSay(player, name, "I can navigate the world. Watch me visit a nearby point, then return to you.");
        Location destination = player.getLocation().clone().add(player.getLocation().getDirection().normalize().multiply(6.0));
        agent.getPathfinder().moveTo(destination, 1.0);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!agent.isValid()) return;
            if (agent.getLocation().distanceSquared(destination) > 9.0) {
                agent.getPathfinder().stopPathfinding();
                agentSay(player, name, "That route was blocked, so I stopped safely.");
                return;
            }
            agentSay(player, name, "Reached the nearby point. Returning to you now.");
            agent.getPathfinder().moveTo(player.getLocation(), 1.0);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (agent.isValid()) {
                    agent.getPathfinder().stopPathfinding();
                    agentSay(player, name, "Demo complete. You can ask me to speak, follow you, stop, or visit a saved waypoint.");
                }
            }, 100L);
        }, 100L);
        return true;
    }

    private boolean ask(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("Use /genecraft ask <agent-name> <what you want it to do>");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§d[GeneCraft] §fI can't find that agent nearby. Spawn one with /genecraft spawn " + clean(args[1], 24) + ".");
            return true;
        }
        String prompt = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        requestPlan(player, agent, prompt, false);
        return true;
    }

    private void requestPlan(Player player, Mob agent, String prompt, boolean autonomous) {
        String name = agentName(agent);
        UUID agentId = agent.getUniqueId();
        if (!pendingPlans.add(agentId)) {
            if (!autonomous) player.sendMessage("§d[GeneCraft] §f" + name + " is finishing the last reply first — try your message again in a moment.");
            return;
        }
        if (!autonomous) player.sendMessage("§b[GeneCraft] §fThinking about that request…");

        JsonObject body = new JsonObject();
        body.addProperty("agent", name);
        body.addProperty("prompt", prompt);
        body.add("context", context(player, agent));
        body.addProperty("goal", getConfig().getString(statePath(agent) + ".goal", ""));
        Set<String> inboxIds = readInbox(agent).stream().map(message -> String.valueOf(message.get("id")))
                .collect(java.util.stream.Collectors.toSet());
        pendingInboxSnapshots.put(agentId, inboxIds);
        bridgeRequest("POST", "/agent/plan", body).whenComplete((response, error) -> Bukkit.getScheduler().runTask(this, () -> {
            pendingPlans.remove(agentId);
            Set<String> capturedInbox = pendingInboxSnapshots.remove(agentId);
            if (invalidatedPlans.remove(agentId)) {
                if (player.isOnline() && !autonomous) player.sendMessage("§d[GeneCraft] §fStopped. I discarded the unfinished reply.");
                return;
            }
            if (!player.isOnline() || (autonomous && !getConfig().getBoolean(statePath(agent) + ".autonomy.enabled", false))) return;
            if (error != null) {
                if (!autonomous) player.sendMessage("§c[GeneCraft] §f" + friendlyBridgeError(error));
                return;
            }
            JsonObject result = parseObject(response);
            if (result == null || result.has("error")) {
                if (!autonomous) player.sendMessage("§c[GeneCraft] §f" + (result == null ? "The local AI bridge returned an unreadable response." : clean(result.get("error").getAsString(), 200)));
                return;
            }
            Entity found = Bukkit.getEntity(agentId);
            if (!(found instanceof Mob liveAgent) || !liveAgent.isValid()) {
                if (!autonomous) player.sendMessage("§b[GeneCraft] §fThe agent is no longer in the world, so I discarded the action.");
                return;
            }
            consumeInboxSnapshot(liveAgent, capturedInbox == null ? Set.of() : capturedInbox);
            if (!autonomous) sendChatGptPlanMessage(player, false);
            executePlan(player, liveAgent, result);
        }));
    }

    private boolean stop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Use /genecraft stop <agent-name>");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§d[GeneCraft] §fI can't find that agent nearby.");
        } else {
            invalidatePendingPlan(agent);
            cancelFollow(agent.getUniqueId());
            cancelJob(player, agent, true);
            agent.getPathfinder().stopPathfinding();
            player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " stopped.");
        }
        return true;
    }

    private boolean jobCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("Use /genecraft job <agent> status or cancel");
            return true;
        }
        Mob agent = findAgentWithSavedJob(player, args[1]);
        if (agent == null) agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§d[GeneCraft] §fI can't find that agent nearby.");
            return true;
        }
        if (args[2].equalsIgnoreCase("cancel")) {
            invalidatePendingPlan(agent);
            cancelJob(player, agent, true);
            return true;
        }
        if (!args[2].equalsIgnoreCase("status")) {
            player.sendMessage("Use /genecraft job " + agentName(agent) + " status or cancel");
            return true;
        }
        String path = jobPath(agent);
        String kind = getConfig().getString(path + ".kind", "");
        String status = getConfig().getString(path + ".status", "");
        if (kind.isBlank() || status.isBlank()) {
            player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " has no saved work order.");
            return true;
        }
        int progress = getConfig().getInt(path + ".progress", 0);
        int target = getConfig().getInt(path + ".target", getConfig().getInt(path + ".length", 0));
        player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + ": " + kind.replace('_', ' ')
                + " — " + status.replace('_', ' ') + (target > 0 ? " (" + progress + "/" + target + ")" : ""));
        return true;
    }

    private boolean supplies(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        var section = getConfig().getConfigurationSection(ownerSupplyPath(player.getUniqueId()));
        if (section == null || section.getKeys(false).isEmpty()) {
            player.sendMessage("§d[GeneCraft] §fYour shared agent supplies are empty. Ask an agent to gather wood or mine.");
            return true;
        }
        List<String> counts = section.getKeys(false).stream().sorted()
                .map(material -> material.toLowerCase(Locale.ROOT) + " × " + section.getInt(material))
                .toList();
        player.sendMessage("§d[GeneCraft] §fShared GeneCraft supplies: " + String.join(", ", counts));
        return true;
    }

    private String jobPath(Mob agent) {
        return statePath(agent) + ".job";
    }

    private String ownerSupplyPath(UUID ownerId) {
        return "owners." + ownerId + ".supplies";
    }

    private boolean activeJob(Mob agent) {
        String status = getConfig().getString(jobPath(agent) + ".status", "");
        return status.equals("running") || status.startsWith("waiting_");
    }

    private boolean canStartJob(Player player, Mob agent) {
        if (activeJob(agent)) {
            player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " already has a work order. Say @" + agentName(agent)
                    + " stop, or use /genecraft job " + agentName(agent) + " cancel.");
            return false;
        }
        long running = ownedAgents(player).stream().filter(this::activeJob).count();
        if (running >= 3) {
            player.sendMessage("§d[GeneCraft] §fYou already have three active work orders. Finish or cancel one first.");
            return false;
        }
        if (!agent.getWorld().equals(player.getWorld()) || agent.getLocation().distance(player.getLocation()) > PLAYER_ACTION_LIMIT) {
            player.sendMessage("§d[GeneCraft] §fBring the agent within " + (int) PLAYER_ACTION_LIMIT + " blocks to give it a work order.");
            return false;
        }
        return true;
    }

    private void startGatherWood(Player player, Mob agent, int targetLogs) {
        if (!canStartJob(player, agent)) return;
        cancelFollow(agent.getUniqueId());
        agent.getPathfinder().stopPathfinding();
        Map<String, Object> job = new java.util.LinkedHashMap<>();
        job.put("kind", "gather_wood");
        job.put("status", "running");
        job.put("world", agent.getWorld().getName());
        // Center the first search on the player who gave the order. Agents can
        // spawn a few blocks away, and the owner may have moved to the trees.
        job.put("anchor_x", player.getLocation().getBlockX());
        job.put("anchor_y", player.getLocation().getBlockY());
        job.put("anchor_z", player.getLocation().getBlockZ());
        job.put("target", targetLogs);
        job.put("progress", 0);
        job.put("created_at", System.currentTimeMillis());
        getConfig().set(jobPath(agent), job);
        saveConfig();
        agentSay(player, agentName(agent), "On it. I’ll collect up to " + targetLogs
                + " logs from trees within " + WOOD_SEARCH_RADIUS
                + " blocks of you and add the planks to our shared supplies. Stay within 32 blocks so I can keep working.");
    }

    private void startStripMine(Player player, Mob agent, int length, int yLevel) {
        if (!canStartJob(player, agent)) return;
        if (agent.getLocation().getBlockY() != yLevel || player.getLocation().getBlockY() != yLevel) {
            agentSay(player, agentName(agent), "I can do that, but bring both of us to foot-level Y=" + yLevel
                    + " first. I won’t excavate a remote tunnel through the world.");
            return;
        }
        cancelFollow(agent.getUniqueId());
        agent.getPathfinder().stopPathfinding();
        Vector direction = player.getLocation().getDirection();
        int dx = Math.abs(direction.getX()) > Math.abs(direction.getZ())
                ? (direction.getX() >= 0 ? 1 : -1) : 0;
        int dz = dx == 0 ? (direction.getZ() >= 0 ? 1 : -1) : 0;
        int startX = agent.getLocation().getBlockX() + dx;
        int startZ = agent.getLocation().getBlockZ() + dz;
        Map<String, Object> job = new java.util.LinkedHashMap<>();
        job.put("kind", "strip_mine");
        job.put("status", "running");
        job.put("world", agent.getWorld().getName());
        job.put("start_x", startX);
        job.put("start_y", yLevel);
        job.put("start_z", startZ);
        job.put("anchor_x", startX);
        job.put("anchor_y", yLevel);
        job.put("anchor_z", startZ);
        job.put("direction_x", dx);
        job.put("direction_z", dz);
        job.put("length", length);
        job.put("progress", 0);
        job.put("created_at", System.currentTimeMillis());
        getConfig().set(jobPath(agent), job);
        saveConfig();
        agentSay(player, agentName(agent), "I’ll cut a 1-wide, 2-high tunnel at Y=" + yLevel + " for " + length
                + " blocks, following the direction you’re facing. I’ll stop at lava, water, or protected blocks.");
    }

    private void startHouseBuild(Player player, Mob agent, JsonArray blueprint, JsonArray sources) {
        if (!canStartJob(player, agent)) return;
        cancelFollow(agent.getUniqueId());
        agent.getPathfinder().stopPathfinding();
        World world = agent.getWorld();
        Vector look = player.getLocation().getDirection();
        int fx = Math.abs(look.getX()) > Math.abs(look.getZ()) ? (look.getX() >= 0 ? 1 : -1) : 0;
        int fz = fx == 0 ? (look.getZ() >= 0 ? 1 : -1) : 0;
        int rx = -fz;
        int rz = fx;
        int centerX = player.getLocation().getBlockX() + fx * 4;
        int centerZ = player.getLocation().getBlockZ() + fz * 4;
        BlockFace doorFacing = fx > 0 ? BlockFace.WEST : fx < 0 ? BlockFace.EAST
                : fz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        int originX = centerX - rx * 2;
        int originZ = centerZ - rz * 2;
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 5; z++) {
                int surfaceX = originX + rx * x + fx * z;
                int surfaceZ = originZ + rz * x + fz * z;
                if (!world.isChunkLoaded(surfaceX >> 4, surfaceZ >> 4)) {
                    agentSay(player, agentName(agent), "The house site reaches an unloaded chunk. Move closer and try again.");
                    return;
                }
                int surface = groundSurfaceY(world, surfaceX, surfaceZ);
                minSurface = Math.min(minSurface, surface);
                maxSurface = Math.max(maxSurface, surface);
            }
        }
        if (maxSurface - minSurface > 1) {
            agentSay(player, agentName(agent), "That house site is too uneven. Find a flatter clearing and give me the order again.");
            return;
        }
        int baseY = maxSurface + 1;
        List<Map<String, Object>> placedBlocks = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int doors = 0;
        for (JsonElement element : blueprint) {
            JsonObject item = element.getAsJsonObject();
            int x = item.get("x").getAsInt();
            int y = item.get("y").getAsInt();
            int z = item.get("z").getAsInt();
            String materialName = item.get("material").getAsString();
            Material material = Material.matchMaterial(materialName);
            if (!validBlueprintBlock(x, y, z, material) || !used.add(x + "," + y + "," + z)) {
                agentSay(player, agentName(agent), "The house plan had a block or coordinate I can’t safely place, so I rejected the plan.");
                return;
            }
            if (isWoodenDoor(material)) {
                if (y >= 6 || used.contains(x + "," + (y + 1) + "," + z)) {
                    agentSay(player, agentName(agent), "That door doesn’t fit the house plan, so I rejected the blueprint.");
                    return;
                }
                doors++;
            }
            int blockX = originX + rx * x + fx * z;
            int blockZ = originZ + rz * x + fz * z;
            if (baseY + y < world.getMinHeight() || baseY + y >= world.getMaxHeight()
                    || (material == Material.OAK_DOOR && baseY + y + 1 >= world.getMaxHeight())) {
                agentSay(player, agentName(agent), "That house plan would cross the world-height boundary, so I rejected it.");
                return;
            }
            Block block = world.getBlockAt(blockX, baseY + y, blockZ);
            if (!block.getType().isAir()) {
                agentSay(player, agentName(agent), "That house footprint isn’t clear. Move to an open, fairly flat spot and ask me again.");
                return;
            }
            if (isWoodenDoor(material) && !world.getBlockAt(blockX, baseY + y + 1, blockZ).getType().isAir()) {
                agentSay(player, agentName(agent), "The doorway is blocked. I won’t overwrite anything in the build area.");
                return;
            }
            Map<String, Object> cell = new java.util.LinkedHashMap<>();
            cell.put("x", blockX);
            cell.put("y", baseY + y);
            cell.put("z", blockZ);
            cell.put("material", material.name());
            placedBlocks.add(cell);
        }
        Set<String> worldPositions = new HashSet<>();
        for (Map<String, Object> cell : placedBlocks) {
            worldPositions.add(cell.get("x") + "," + cell.get("y") + "," + cell.get("z"));
        }
        for (Map<String, Object> cell : placedBlocks) {
            Material doorMaterial = Material.matchMaterial(String.valueOf(cell.get("material")));
            if (isWoodenDoor(doorMaterial)
                    && worldPositions.contains(cell.get("x") + "," + (((Number) cell.get("y")).intValue() + 1) + "," + cell.get("z"))) {
                agentSay(player, agentName(agent), "The house plan overlaps its door. I rejected it before changing the world.");
                return;
            }
        }
        if (placedBlocks.size() + doors > 120 || !isCoherentHouseBlueprint(blueprint)) {
            agentSay(player, agentName(agent), "That plan needs a complete floor, walls, and roof within the build limit. I rejected it before changing the world.");
            return;
        }
        Map<String, Object> job = new java.util.LinkedHashMap<>();
        job.put("kind", "build_house");
        job.put("status", "waiting_for_materials");
        job.put("world", world.getName());
        job.put("anchor_x", centerX);
        job.put("anchor_y", baseY);
        job.put("anchor_z", centerZ);
        job.put("worker_x", centerX - fx + 0.5);
        job.put("worker_y", baseY);
        job.put("worker_z", centerZ - fz + 0.5);
        job.put("door_facing", doorFacing.name());
        job.put("blocks", placedBlocks);
        job.put("progress", 0);
        job.put("created_at", System.currentTimeMillis());
        if (sources != null && !sources.isEmpty()) {
            List<Map<String, Object>> sourceList = new ArrayList<>();
            for (JsonElement element : sources) {
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();
                String url = source.has("url") ? source.get("url").getAsString() : "";
                String title = source.has("title") ? clean(source.get("title").getAsString(), 100) : "Tutorial source";
                if (url.startsWith("https://") && url.length() <= 500) sourceList.add(Map.of("url", url, "title", title));
            }
            job.put("sources", sourceList);
        }
        getConfig().set(jobPath(agent), job);
        saveConfig();
        agentSay(player, agentName(agent), "I’ve made a " + placedBlocks.size()
                + "-block starter-house plan. I’ll build it when our shared supplies contain the materials it needs.");
    }

    private boolean validBlueprintBlock(int x, int y, int z, Material material) {
        if (x < 0 || x > 5 || y < 0 || y > 6 || z < 0 || z > 5 || material == null) return false;
        return (material.name().endsWith("_PLANKS") && material.isBlock())
                || isWoodenDoor(material)
                || Set.of(Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.GRANITE,
                Material.DIORITE, Material.ANDESITE, Material.TUFF).contains(material);
    }

    private int groundSurfaceY(World world, int x, int z) {
        for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            String name = material.name();
            if (!material.isSolid() || isTreeLog(material) || name.endsWith("_LEAVES") || name.equals("VINE")) continue;
            return y + 1;
        }
        return world.getMinHeight();
    }

    private boolean isWoodenDoor(Material material) {
        if (material == null || !material.name().endsWith("_DOOR") || !material.isBlock()) return false;
        String wood = material.name().substring(0, material.name().length() - "_DOOR".length());
        return Set.of("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE",
                "CHERRY", "BAMBOO", "CRIMSON", "WARPED", "PALE_OAK").contains(wood);
    }

    private Material planksForDoor(Material door) {
        if (!isWoodenDoor(door)) return null;
        return Material.matchMaterial(door.name().substring(0, door.name().length() - "_DOOR".length()) + "_PLANKS");
    }

    private boolean isCoherentHouseBlueprint(JsonArray blueprint) {
        int maxX = 0;
        int maxZ = 0;
        int maxY = 0;
        int floorBlocks = 0;
        Map<Integer, Integer> layerCounts = new HashMap<>();
        for (JsonElement element : blueprint) {
            JsonObject item = element.getAsJsonObject();
            int x = item.get("x").getAsInt();
            int y = item.get("y").getAsInt();
            int z = item.get("z").getAsInt();
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
            maxY = Math.max(maxY, y);
            if (y == 0) floorBlocks++;
            layerCounts.merge(y, 1, Integer::sum);
        }
        if (maxX < 3 || maxZ < 3 || maxY < 3 || floorBlocks < 12 || layerCounts.getOrDefault(maxY, 0) < 10) return false;
        for (int y = 1; y < maxY; y++) {
            if (layerCounts.getOrDefault(y, 0) < 6) return false;
        }
        return true;
    }

    private void sendSourceLinks(Player player, JsonArray sources) {
        if (sources == null) return;
        for (JsonElement element : sources) {
            if (!element.isJsonObject()) continue;
            JsonObject source = element.getAsJsonObject();
            if (!source.has("url") || !source.has("title")) continue;
            String url = source.get("url").getAsString();
            if (!url.startsWith("https://") || url.length() > 500) continue;
            String title = clean(source.get("title").getAsString(), 100);
            player.sendMessage(Component.text("[GeneCraft house reference] " + title, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Open the tutorial used for the design"))));
        }
    }

    private void cancelJob(Player player, Mob agent, boolean refund) {
        String path = jobPath(agent);
        if (!activeJob(agent)) {
            if (player != null) player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " has no active work order.");
            return;
        }
        if (refund) refundUnbuiltHouse(agent);
        getConfig().set(path + ".status", "cancelled");
        saveConfig();
        agent.getPathfinder().stopPathfinding();
        if (player != null) agentSay(player, agentName(agent), "Work order cancelled. I’ve stopped safely.");
    }

    private boolean isDirectCancel(String prompt) {
        return prompt.trim().toLowerCase(Locale.ROOT).matches(
                "(?:stop|stop it|stop moving|stop work|stop working|stop the work|stop the job|stop this job|"
                        + "cancel|cancel it|cancel that|cancel the job|cancel this job|cancel work|cancel the work|abort)(?:[.!?]+)?");
    }

    private void invalidatePendingPlan(Mob agent) {
        if (pendingPlans.contains(agent.getUniqueId())) invalidatedPlans.add(agent.getUniqueId());
    }

    private void refundUnbuiltHouse(Mob agent) {
        String path = jobPath(agent);
        if (!getConfig().getBoolean(path + ".materials_reserved", false)) return;
        List<Map<?, ?>> blocks = getConfig().getMapList(path + ".blocks");
        int progress = getConfig().getInt(path + ".progress", 0);
        String owner = agent.getPersistentDataContainer().get(agentOwnerKey, PersistentDataType.STRING);
        if (owner == null) return;
        UUID ownerId;
        try {
            ownerId = UUID.fromString(owner);
        } catch (IllegalArgumentException exception) {
            return;
        }
        for (int index = progress; index < blocks.size(); index++) {
            Object raw = blocks.get(index).get("material");
            if (raw instanceof String materialName) {
                Material material = Material.matchMaterial(materialName);
                if (isWoodenDoor(material)) adjustSupply(ownerId, planksForDoor(material), 6);
                else if (material != null) adjustSupply(ownerId, material, 1);
            }
        }
        getConfig().set(path + ".materials_reserved", false);
        saveConfig();
    }

    private void tickJobs() {
        for (Player owner : Bukkit.getOnlinePlayers()) {
            for (Mob agent : ownedAgents(owner)) {
                if (!activeJob(agent)) continue;
                if (!agent.getWorld().equals(owner.getWorld())
                        || agent.getLocation().distance(owner.getLocation()) > PLAYER_ACTION_LIMIT) {
                    agent.getPathfinder().stopPathfinding();
                    changeJobStatus(owner, agent, "waiting_for_owner",
                            "I paused the work because you’re more than 32 blocks away. Come back nearby and I’ll resume.");
                    continue;
                }
                if (getConfig().getString(jobPath(agent) + ".status", "").equals("waiting_for_owner")) {
                    changeJobStatus(owner, agent, "running", "You’re back nearby. I’m resuming the work.");
                }
                String kind = getConfig().getString(jobPath(agent) + ".kind", "");
                switch (kind) {
                    case "gather_wood" -> tickGatherWood(owner, agent);
                    case "strip_mine" -> tickStripMine(owner, agent);
                    case "build_house" -> tickHouseBuild(owner, agent);
                    default -> finishJob(owner, agent, "failed", "I found an unknown work order and stopped it safely.");
                }
            }
        }
    }

    private void tickGatherWood(Player owner, Mob agent) {
        String path = jobPath(agent);
        int target = getConfig().getInt(path + ".target", 0);
        int gathered = getConfig().getInt(path + ".progress", 0);
        if (target < 1 || target > 64) {
            finishJob(owner, agent, "failed", "That wood target is outside my work limit, so I stopped.");
            return;
        }
        if (gathered >= target) {
            finishJob(owner, agent, "completed", "Wood-gathering job complete. I added " + gathered + " logs’ worth of planks to our shared supplies.");
            return;
        }
        String worldName = getConfig().getString(path + ".world", "");
        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.equals(agent.getWorld())) return;
        int targetX = getConfig().getInt(path + ".target_x", Integer.MIN_VALUE);
        int targetY = getConfig().getInt(path + ".target_y", Integer.MIN_VALUE);
        int targetZ = getConfig().getInt(path + ".target_z", Integer.MIN_VALUE);
        Block log = null;
        if (targetX != Integer.MIN_VALUE && world.isChunkLoaded(targetX >> 4, targetZ >> 4)) {
            Block existing = world.getBlockAt(targetX, targetY, targetZ);
            if (isTreeLog(existing.getType())) log = existing;
        }
        if (log == null) {
            long nextSearchAt = getConfig().getLong(path + ".next_search_at", 0L);
            if (System.currentTimeMillis() < nextSearchAt) return;
            log = findNearbyLog(world, owner, agent);
            if (log == null) {
                getConfig().set(path + ".next_search_at", System.currentTimeMillis() + WOOD_RESCAN_DELAY_MS);
                saveConfig();
                changeJobStatus(owner, agent, "waiting_for_logs", "I can’t find a tree within "
                        + WOOD_SEARCH_RADIUS + " blocks of you yet. Move us closer to trees and I’ll keep looking.");
                return;
            }
            getConfig().set(path + ".next_search_at", null);
            clearGatherTarget(path);
            getConfig().set(path + ".target_x", log.getX());
            getConfig().set(path + ".target_y", log.getY());
            getConfig().set(path + ".target_z", log.getZ());
            saveConfig();
            changeJobStatus(owner, agent, "running", "I found another tree. I’m continuing the wood run.");
        }
        Location approach = savedLogApproach(path, world, log);
        if (approach == null) {
            approach = findLogApproach(agent, log);
            if (approach != null) {
                getConfig().set(path + ".approach_x", approach.getBlockX());
                getConfig().set(path + ".approach_y", approach.getBlockY());
                getConfig().set(path + ".approach_z", approach.getBlockZ());
                saveConfig();
            }
        }
        if (approach == null) {
            clearGatherTarget(path);
            getConfig().set(path + ".next_search_at", System.currentTimeMillis() + WOOD_RESCAN_DELAY_MS);
            saveConfig();
            changeJobStatus(owner, agent, "waiting_for_logs", "I found a tree but can’t reach its trunk from here. Move us to clearer ground and I’ll try again.");
            return;
        }
        if (agent.getLocation().distance(approach) > 2.8) {
            var pathfinder = agent.getPathfinder();
            boolean alreadyHeadingThere = pathfinder.hasPath()
                    && getConfig().getInt(path + ".route_x", Integer.MIN_VALUE) == approach.getBlockX()
                    && getConfig().getInt(path + ".route_y", Integer.MIN_VALUE) == approach.getBlockY()
                    && getConfig().getInt(path + ".route_z", Integer.MIN_VALUE) == approach.getBlockZ();
            // Reissuing the same move every half second can interrupt navigation
            // and make an agent appear to wander. Keep the active path until it
            // reaches its destination or actually fails.
            if (!alreadyHeadingThere) {
                if (!pathfinder.moveTo(approach, 1.0)) {
                    clearGatherTarget(path);
                    getConfig().set(path + ".next_search_at", System.currentTimeMillis() + WOOD_RESCAN_DELAY_MS);
                    saveConfig();
                    changeJobStatus(owner, agent, "waiting_for_logs", "I found a tree but can’t path to it. I’ll look for another reachable tree nearby.");
                } else {
                    getConfig().set(path + ".route_x", approach.getBlockX());
                    getConfig().set(path + ".route_y", approach.getBlockY());
                    getConfig().set(path + ".route_z", approach.getBlockZ());
                    saveConfig();
                }
            }
            return;
        }
        agent.getPathfinder().stopPathfinding();
        Material plank = planksForLog(log.getType());
        if (plank == null) {
            clearGatherTarget(path);
            saveConfig();
            return;
        }
        Location soundAt = log.getLocation();
        log.setType(Material.AIR, false);
        world.playSound(soundAt, org.bukkit.Sound.BLOCK_WOOD_BREAK, 0.8f, 1.0f);
        adjustSupply(owner.getUniqueId(), plank, 4);
        getConfig().set(path + ".progress", gathered + 1);
        clearGatherTarget(path);
        saveConfig();
        if ((gathered + 1) % 4 == 0) {
            agentSay(owner, agentName(agent), "I’ve collected " + (gathered + 1) + " logs so far.");
        }
    }

    private void clearGatherTarget(String path) {
        for (String key : List.of("target_x", "target_y", "target_z", "approach_x", "approach_y", "approach_z", "route_x", "route_y", "route_z")) {
            getConfig().set(path + "." + key, null);
        }
    }

    private Location savedLogApproach(String path, World world, Block log) {
        int x = getConfig().getInt(path + ".approach_x", Integer.MIN_VALUE);
        int y = getConfig().getInt(path + ".approach_y", Integer.MIN_VALUE);
        int z = getConfig().getInt(path + ".approach_z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE
                || Math.abs(x - log.getX()) > 1 || Math.abs(z - log.getZ()) > 1
                || !isStandable(world, x, y, z)) return null;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private boolean isStandable(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4) || y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                && !floor.isPassable() && !floor.isLiquid();
    }

    private Block findNearbyLog(World world, Player owner, Mob agent) {
        Location center = owner.getLocation();
        int anchorX = center.getBlockX();
        int anchorY = center.getBlockY();
        int anchorZ = center.getBlockZ();
        int radiusSquared = WOOD_SEARCH_RADIUS * WOOD_SEARCH_RADIUS;
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = anchorX - WOOD_SEARCH_RADIUS; x <= anchorX + WOOD_SEARCH_RADIUS; x++) {
            for (int z = anchorZ - WOOD_SEARCH_RADIUS; z <= anchorZ + WOOD_SEARCH_RADIUS; z++) {
                int dx = x - anchorX;
                int dz = z - anchorZ;
                if (dx * dx + dz * dz > radiusSquared) continue;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(world.getMinHeight(), anchorY - 16); y <= Math.min(world.getMaxHeight() - 1, anchorY + 24); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!isTreeLog(block.getType())) continue;
                    double distance = block.getLocation().distanceSquared(agent.getLocation());
                    if (distance < bestDistance) {
                        best = block;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private Location findLogApproach(Mob agent, Block log) {
        World world = log.getWorld();
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] offset : offsets) {
            int x = log.getX() + offset[0];
            int z = log.getZ() + offset[1];
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int surfaceY = world.getHighestBlockYAt(x, z);
            for (int y = Math.max(world.getMinHeight() + 1, surfaceY - 24); y <= Math.min(world.getMaxHeight() - 2, surfaceY + 1); y++) {
                if (!isStandable(world, x, y, z)) continue;
                Location candidate = new Location(world, x + 0.5, y, z + 0.5);
                double distance = candidate.distanceSquared(agent.getLocation());
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean isTreeLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE") || name.equals("BAMBOO_BLOCK");
    }

    private Material planksForLog(Material log) {
        String name = log.name();
        String root = name;
        for (String suffix : List.of("_LOG", "_WOOD", "_STEM", "_HYPHAE")) {
            if (name.endsWith(suffix)) {
                root = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        if (name.equals("BAMBOO_BLOCK")) root = "BAMBOO";
        Material planks = Material.matchMaterial(root + "_PLANKS");
        return planks != null ? planks : Material.OAK_PLANKS;
    }

    private void tickStripMine(Player owner, Mob agent) {
        String path = jobPath(agent);
        int length = getConfig().getInt(path + ".length", 0);
        int progress = getConfig().getInt(path + ".progress", 0);
        if (length < 4 || length > 32) {
            finishJob(owner, agent, "failed", "That tunnel length is outside my work limit, so I stopped.");
            return;
        }
        if (progress >= length) {
            finishJob(owner, agent, "completed", "Strip mine complete. I stopped at the requested length.");
            return;
        }
        World world = Bukkit.getWorld(getConfig().getString(path + ".world", ""));
        if (world == null || !world.equals(agent.getWorld())) return;
        int y = getConfig().getInt(path + ".start_y");
        int dx = getConfig().getInt(path + ".direction_x");
        int dz = getConfig().getInt(path + ".direction_z");
        int x = getConfig().getInt(path + ".start_x") + dx * progress;
        int z = getConfig().getInt(path + ".start_z") + dz * progress;
        if (Math.abs(x - getConfig().getInt(path + ".anchor_x")) > 32
                || Math.abs(z - getConfig().getInt(path + ".anchor_z")) > 32
                || y < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            finishJob(owner, agent, "failed", "That tunnel would leave the protected work area, so I stopped.");
            return;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            finishJob(owner, agent, "stopped_safe", "The tunnel reached an unloaded area. I stopped without loading new chunks.");
            return;
        }
        Block lower = world.getBlockAt(x, y, z);
        Block upper = world.getBlockAt(x, y + 1, z);
        if (!isSafeTunnelSlice(lower, world, x, y, z) || !isSafeTunnelSlice(upper, world, x, y + 1, z)) {
            finishJob(owner, agent, "stopped_safe", "I reached lava, water, or a protected block, so I stopped this tunnel safely.");
            return;
        }
        for (Block block : List.of(lower, upper)) {
            if (block.getType().isAir()) continue;
            Material mined = block.getType();
            block.setType(Material.AIR, false);
            Material supply = mined.equals(Material.STONE) ? Material.COBBLESTONE
                    : mined.equals(Material.DEEPSLATE) ? Material.COBBLED_DEEPSLATE : mined;
            adjustSupply(owner.getUniqueId(), supply, 1);
            world.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_STONE_BREAK, 0.65f, 0.85f);
        }
        getConfig().set(path + ".progress", progress + 1);
        saveConfig();
        Location step = new Location(world, x + 0.5, y, z + 0.5, agent.getLocation().getYaw(), agent.getLocation().getPitch());
        agent.teleport(step);
        if ((progress + 1) % 8 == 0) agentSay(owner, agentName(agent), "I’ve cleared " + (progress + 1) + " of " + length + " tunnel blocks.");
    }

    private boolean isSafeTunnelSlice(Block block, World world, int x, int y, int z) {
        Material material = block.getType();
        if (material.isAir()) return true;
        if (block.isLiquid() || material == Material.BEDROCK || material == Material.BARRIER
                || material == Material.END_PORTAL || material == Material.END_PORTAL_FRAME
                || material == Material.NETHER_PORTAL || material == Material.STRUCTURE_VOID
                || material == Material.COMMAND_BLOCK || material == Material.CHAIN_COMMAND_BLOCK
                || material == Material.REPEATING_COMMAND_BLOCK || block.getState() instanceof Container) return false;
        String name = material.name();
        boolean natural = Set.of("STONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "TUFF", "CALCITE", "DRIPSTONE_BLOCK",
                "GRANITE", "DIORITE", "ANDESITE", "DIRT", "ROOTED_DIRT", "GRAVEL", "SAND", "RED_SAND", "CLAY",
                "NETHERRACK", "BLACKSTONE", "BASALT", "SOUL_SAND", "SOUL_SOIL", "COBBLESTONE").contains(name)
                || name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
        if (!natural) return false;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            if (!world.isChunkLoaded((x + side[0]) >> 4, (z + side[1]) >> 4)) continue;
            Material neighbor = world.getBlockAt(x + side[0], y, z + side[1]).getType();
            if (neighbor == Material.LAVA || neighbor == Material.WATER) return false;
        }
        return true;
    }

    private void tickHouseBuild(Player owner, Mob agent) {
        String path = jobPath(agent);
        World world = Bukkit.getWorld(getConfig().getString(path + ".world", ""));
        if (world == null || !world.equals(agent.getWorld())) return;
        List<Map<?, ?>> blocks = getConfig().getMapList(path + ".blocks");
        int progress = getConfig().getInt(path + ".progress", 0);
        if (progress >= blocks.size()) {
            finishJob(owner, agent, "completed", "The house is finished! Check our shared supplies for what’s left.");
            return;
        }
        if (!getConfig().getBoolean(path + ".materials_reserved", false)) {
            if (!reserveHouseMaterials(owner, agent, blocks)) return;
        }
        Location workSpot = new Location(world, getConfig().getDouble(path + ".worker_x"),
                getConfig().getDouble(path + ".worker_y"), getConfig().getDouble(path + ".worker_z"));
        if (agent.getLocation().distance(workSpot) > 2.25) {
            agent.getPathfinder().moveTo(workSpot, 1.0);
            return;
        }
        agent.getPathfinder().stopPathfinding();
        Map<?, ?> cell = blocks.get(progress);
        int x = ((Number) cell.get("x")).intValue();
        int y = ((Number) cell.get("y")).intValue();
        int z = ((Number) cell.get("z")).intValue();
        Material material = Material.matchMaterial(String.valueOf(cell.get("material")));
        Block target = world.getBlockAt(x, y, z);
        if (!validBlueprintBlock(0, 0, 0, material) || !target.getType().isAir()) {
            refundUnbuiltHouse(agent);
            finishJob(owner, agent, "stopped_safe", "Something entered the build footprint. I stopped and refunded the blocks I hadn’t placed.");
            return;
        }
        if (isWoodenDoor(material)) {
            Block upper = world.getBlockAt(x, y + 1, z);
            if (!upper.getType().isAir()) {
                refundUnbuiltHouse(agent);
                finishJob(owner, agent, "stopped_safe", "Something blocked the doorway. I stopped and refunded the blocks I hadn’t placed.");
                return;
            }
            BlockFace facing;
            try {
                facing = BlockFace.valueOf(getConfig().getString(path + ".door_facing", "NORTH"));
            } catch (IllegalArgumentException exception) {
                facing = BlockFace.NORTH;
            }
            Door bottom = (Door) material.createBlockData();
            bottom.setHalf(Bisected.Half.BOTTOM);
            bottom.setFacing(facing);
            Door top = (Door) material.createBlockData();
            top.setHalf(Bisected.Half.TOP);
            top.setFacing(facing);
            target.setType(material, false);
            target.setBlockData(bottom, false);
            upper.setType(material, false);
            upper.setBlockData(top, false);
        } else {
            target.setType(material, false);
        }
        agent.swingMainHand();
        getConfig().set(path + ".progress", progress + 1);
        saveConfig();
        if ((progress + 1) % 16 == 0) agentSay(owner, agentName(agent), "The house is taking shape — " + (progress + 1) + " of " + blocks.size() + " blocks placed.");
    }

    private boolean reserveHouseMaterials(Player owner, Mob agent, List<Map<?, ?>> blocks) {
        String path = jobPath(agent);
        Map<Material, Integer> needed = new HashMap<>();
        for (Map<?, ?> block : blocks) {
            Material material = Material.matchMaterial(String.valueOf(block.get("material")));
            if (material == null) {
                finishJob(owner, agent, "failed", "I found an invalid material in the house blueprint and stopped.");
                return false;
            }
            if (isWoodenDoor(material)) needed.merge(planksForDoor(material), 6, Integer::sum);
            else needed.merge(material, 1, Integer::sum);
        }
        for (Map.Entry<Material, Integer> entry : needed.entrySet()) {
            if (supplyCount(owner.getUniqueId(), entry.getKey()) < entry.getValue()) {
                changeJobStatus(owner, agent, "waiting_for_materials", "I’ve got the house plan, but I’m waiting for "
                        + entry.getValue() + " " + entry.getKey().name().toLowerCase(Locale.ROOT) + ". Ask another agent to gather or mine the materials.");
                return false;
            }
        }
        needed.forEach((material, count) -> adjustSupply(owner.getUniqueId(), material, -count));
        getConfig().set(path + ".materials_reserved", true);
        changeJobStatus(owner, agent, "running", "The supplies are ready. I’m starting the house now.");
        saveConfig();
        return true;
    }

    private int supplyCount(UUID ownerId, Material material) {
        return getConfig().getInt(ownerSupplyPath(ownerId) + "." + material.name(), 0);
    }

    private void adjustSupply(UUID ownerId, Material material, int amount) {
        String key = ownerSupplyPath(ownerId) + "." + material.name();
        int updated = Math.max(0, getConfig().getInt(key, 0) + amount);
        getConfig().set(key, updated == 0 ? null : updated);
    }

    private void changeJobStatus(Player owner, Mob agent, String status, String message) {
        String path = jobPath(agent);
        if (status.equals(getConfig().getString(path + ".status", ""))) return;
        getConfig().set(path + ".status", status);
        saveConfig();
        if (owner != null && owner.isOnline()) agentSay(owner, agentName(agent), message);
    }

    private void finishJob(Player owner, Mob agent, String status, String message) {
        if (activeJob(agent)) {
            getConfig().set(jobPath(agent) + ".status", status);
            saveConfig();
        }
        agent.getPathfinder().stopPathfinding();
        if (owner != null && owner.isOnline()) agentSay(owner, agentName(agent), message);
    }

    private boolean removeAgent(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command in the game as a player.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Use /genecraft remove <agent-name>");
            return true;
        }
        Mob agent = findAgent(player, args[1]);
        if (agent == null) {
            player.sendMessage("§b[GeneCraft] §fI can't find that agent nearby.");
            return true;
        }
        String name = agentName(agent);
        UUID id = agent.getUniqueId();
        invalidatePendingPlan(agent);
        cancelFollow(id);
        cancelJob(player, agent, true);
        pendingPlans.remove(id);
        pendingInboxSnapshots.remove(id);
        for (Entity passenger : List.copyOf(agent.getPassengers())) passenger.remove();
        agent.remove();
        getConfig().set("agents." + id, null);
        saveConfig();
        player.sendMessage("§b[GeneCraft] §fRemoved " + name + " and its saved agent state.");
        return true;
    }

    private void executePlan(Player player, Mob agent, JsonObject result) {
        String action = result.has("action") ? result.get("action").getAsString() : "";
        JsonObject arguments = result.has("arguments") && result.get("arguments").isJsonObject()
                ? result.getAsJsonObject("arguments") : new JsonObject();
        if (!validPlanArguments(action, arguments)) {
            player.sendMessage("§c[GeneCraft] §fThe AI returned invalid action arguments; nothing was executed.");
            return;
        }
        if (result.has("sources") && result.get("sources").isJsonArray()) {
            sendSourceLinks(player, result.getAsJsonArray("sources"));
        }
        String name = agentName(agent);
        switch (action) {
            case "speak" -> {
                String text = clean(arguments.get("text").getAsString(), 160);
                if (text.isBlank()) {
                    player.sendMessage("§d[GeneCraft] §fThe agent had no message to say.");
                } else {
                    agentSay(player, name, text);
                }
            }
            case "follow_player" -> {
                if (activeJob(agent)) {
                    player.sendMessage("§d[GeneCraft] §f" + name + " is busy with a saved work order. Cancel it first if you want them to follow you.");
                } else if (!agent.getWorld().equals(player.getWorld()) || agent.getLocation().distance(player.getLocation()) > PLAYER_ACTION_LIMIT) {
                    player.sendMessage("§d[GeneCraft] §fI only follow within " + (int) PLAYER_ACTION_LIMIT + " blocks.");
                } else {
                    beginFollow(player, agent);
                }
            }
            case "go_to_waypoint" -> {
                String waypoint = arguments.get("name").getAsString().toLowerCase(Locale.ROOT);
                Location destination = waypointLocation(waypoint);
                if (destination == null) {
                    player.sendMessage("§d[GeneCraft] §fThat saved waypoint doesn't exist.");
                } else if (!destination.getWorld().equals(agent.getWorld())
                        || destination.distance(agent.getLocation()) > WAYPOINT_ACTION_LIMIT) {
                    player.sendMessage("§d[GeneCraft] §fThat waypoint is outside this local demo's 64-block movement limit.");
                } else if (activeJob(agent)) {
                    player.sendMessage("§d[GeneCraft] §f" + name + " is busy with a saved work order. Cancel it before changing destinations.");
                } else {
                    cancelFollow(agent.getUniqueId());
                    boolean started = agent.getPathfinder().moveTo(destination, 1.0);
                    player.sendMessage(started ? "§d[GeneCraft] §f" + name + " is heading to '" + waypoint + "'."
                            : "§d[GeneCraft] §fI couldn't find a path to that waypoint.");
                }
            }
            case "stop_moving" -> {
                cancelFollow(agent.getUniqueId());
                if (activeJob(agent)) cancelJob(player, agent, true);
                agent.getPathfinder().stopPathfinding();
                player.sendMessage("§b[GeneCraft] §f" + name + " stopped moving.");
            }
            case "remember" -> {
                String note = clean(arguments.get("note").getAsString(), 160).trim();
                remember(agent, note);
                if (getConfig().getBoolean(statePath(agent) + ".autonomy.enabled", false)) {
                    player.sendActionBar(Component.text(name + " saved a memory", NamedTextColor.LIGHT_PURPLE));
                } else {
                    player.sendMessage("§b[GeneCraft] §f" + name + " remembered: " + note);
                }
            }
            case "message_agent" -> sendAgentMessage(player, agent, arguments.get("recipient").getAsString(), arguments.get("text").getAsString());
            case "start_gather_wood" -> startGatherWood(player, agent, arguments.get("target_logs").getAsInt());
            case "start_strip_mine" -> startStripMine(player, agent, arguments.get("length").getAsInt(), arguments.get("y_level").getAsInt());
            case "start_house_build" -> startHouseBuild(player, agent, arguments.getAsJsonArray("blueprint"),
                    result.has("sources") && result.get("sources").isJsonArray() ? result.getAsJsonArray("sources") : null);
            case "cancel_job" -> cancelJob(player, agent, true);
            default -> player.sendMessage("§c[GeneCraft] §fThe AI requested an action this version does not allow; nothing was executed.");
        }
    }

    private boolean validPlanArguments(String action, JsonObject arguments) {
        return switch (action) {
            case "speak" -> arguments.size() == 1
                    && arguments.has("text")
                    && arguments.get("text").isJsonPrimitive()
                    && arguments.getAsJsonPrimitive("text").isString()
                    && !arguments.get("text").getAsString().isBlank()
                    && arguments.get("text").getAsString().length() <= 160;
            case "go_to_waypoint" -> arguments.size() == 1
                    && arguments.has("name")
                    && arguments.get("name").isJsonPrimitive()
                    && arguments.getAsJsonPrimitive("name").isString()
                    && isWaypointName(arguments.get("name").getAsString());
            case "message_agent" -> arguments.size() == 2
                    && arguments.has("recipient") && arguments.get("recipient").isJsonPrimitive()
                    && arguments.getAsJsonPrimitive("recipient").isString()
                    && arguments.get("recipient").getAsString().matches("[A-Za-z0-9_-]{1,24}")
                    && arguments.has("text") && arguments.get("text").isJsonPrimitive()
                    && arguments.getAsJsonPrimitive("text").isString()
                    && !arguments.get("text").getAsString().isBlank()
                    && arguments.get("text").getAsString().length() <= 120;
            case "remember" -> arguments.size() == 1
                    && arguments.has("note") && arguments.get("note").isJsonPrimitive()
                    && arguments.getAsJsonPrimitive("note").isString()
                    && !arguments.get("note").getAsString().isBlank()
                    && arguments.get("note").getAsString().length() <= 160;
            case "start_gather_wood" -> arguments.size() == 1
                    && arguments.has("target_logs") && arguments.get("target_logs").isJsonPrimitive()
                    && isInteger(arguments.getAsJsonPrimitive("target_logs"))
                    && arguments.get("target_logs").getAsInt() >= 1 && arguments.get("target_logs").getAsInt() <= 64;
            case "start_strip_mine" -> arguments.size() == 3
                    && arguments.has("length") && arguments.get("length").isJsonPrimitive()
                    && isInteger(arguments.getAsJsonPrimitive("length"))
                    && arguments.get("length").getAsInt() >= 4 && arguments.get("length").getAsInt() <= 32
                    && arguments.has("y_level") && arguments.get("y_level").isJsonPrimitive()
                    && isInteger(arguments.getAsJsonPrimitive("y_level"))
                    && arguments.get("y_level").getAsInt() >= -60
                    && arguments.get("y_level").getAsInt() <= 319
                    && arguments.has("direction") && arguments.get("direction").isJsonPrimitive()
                    && "player_facing".equals(arguments.get("direction").getAsString());
            case "start_house_build" -> validHouseBlueprint(arguments);
            case "follow_player", "stop_moving" -> arguments.isEmpty();
            case "cancel_job" -> arguments.isEmpty();
            default -> false;
        };
    }

    private boolean isInteger(com.google.gson.JsonPrimitive value) {
        return value.isNumber() && value.getAsString().matches("-?(0|[1-9][0-9]*)");
    }

    private boolean validHouseBlueprint(JsonObject arguments) {
        if (arguments.size() != 1 || !arguments.has("blueprint") || !arguments.get("blueprint").isJsonArray()) return false;
        JsonArray blueprint = arguments.getAsJsonArray("blueprint");
        if (blueprint.size() < 12 || blueprint.size() > 120) return false;
        Set<String> coordinates = new HashSet<>();
        for (JsonElement element : blueprint) {
            if (!element.isJsonObject()) return false;
            JsonObject block = element.getAsJsonObject();
            if (block.size() != 4 || !block.has("x") || !block.has("y") || !block.has("z") || !block.has("material")
                    || !block.get("x").isJsonPrimitive() || !block.get("y").isJsonPrimitive() || !block.get("z").isJsonPrimitive()
                    || !isInteger(block.getAsJsonPrimitive("x")) || !isInteger(block.getAsJsonPrimitive("y"))
                    || !isInteger(block.getAsJsonPrimitive("z")) || !block.get("material").isJsonPrimitive()
                    || !block.getAsJsonPrimitive("material").isString()) return false;
            int x = block.get("x").getAsInt();
            int y = block.get("y").getAsInt();
            int z = block.get("z").getAsInt();
            Material material = Material.matchMaterial(block.get("material").getAsString());
            if (!validBlueprintBlock(x, y, z, material) || !coordinates.add(x + "," + y + "," + z)) return false;
        }
        return true;
    }

    private JsonObject context(Player player, Mob agent) {
        JsonObject context = new JsonObject();
        context.addProperty("world", agent.getWorld().getName());
        context.add("agent_position", position(agent.getLocation()));
        context.addProperty("agent_health", agent.getHealth());
        context.add("player_position", position(player.getLocation()));
        context.addProperty("player_y_level", player.getLocation().getBlockY());
        Vector look = player.getLocation().getDirection();
        String facing = Math.abs(look.getX()) > Math.abs(look.getZ())
                ? (look.getX() >= 0 ? "east" : "west") : (look.getZ() >= 0 ? "south" : "north");
        context.addProperty("player_facing", facing);
        context.addProperty("world_time_ticks", agent.getWorld().getTime());
        context.addProperty("weather", agent.getWorld().hasStorm() ? "rain" : "clear");
        context.addProperty("ground_block", agent.getLocation().clone().subtract(0, 1, 0).getBlock().getType().getKey().toString());
        context.addProperty("persistent_goal", getConfig().getString(statePath(agent) + ".goal", ""));
        JsonArray nearby = new JsonArray();
        agent.getNearbyEntities(12, 8, 12).stream()
                .filter(entity -> entity != agent)
                .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(agent.getLocation())))
                .limit(10)
                .forEach(entity -> {
                    JsonObject observation = new JsonObject();
                    observation.addProperty("type", entity.getType().name().toLowerCase(Locale.ROOT));
                    observation.addProperty("distance", Math.round(entity.getLocation().distance(agent.getLocation()) * 10.0) / 10.0);
                    if (entity instanceof Enemy) observation.addProperty("threat", true);
                    if (entity instanceof Player nearbyPlayer) {
                        observation.addProperty("type", "player");
                        observation.addProperty("self", nearbyPlayer.getUniqueId().equals(player.getUniqueId()));
                    }
                    nearby.add(observation);
                });
        context.add("nearby_entities", nearby);
        JsonArray nearbyAgents = new JsonArray();
        ownedAgents(player).stream()
                .filter(other -> !other.getUniqueId().equals(agent.getUniqueId()))
                .filter(other -> other.getWorld().equals(agent.getWorld()))
                .filter(other -> other.getLocation().distance(agent.getLocation()) <= 16.0)
                .forEach(other -> nearbyAgents.add(agentName(other)));
        context.add("nearby_agents", nearbyAgents);
        JsonArray waypoints = new JsonArray();
        var section = getConfig().getConfigurationSection("waypoints");
        if (section != null) section.getKeys(false).forEach(waypoints::add);
        context.add("available_waypoints", waypoints);
        JsonArray memories = new JsonArray();
        getConfig().getStringList(statePath(agent) + ".memory").stream().limit(8).forEach(memories::add);
        context.add("memory", memories);
        JsonArray messages = new JsonArray();
        for (Map<String, Object> message : readInbox(agent)) {
            JsonObject item = new JsonObject();
            item.addProperty("id", String.valueOf(message.get("id")));
            item.addProperty("from", String.valueOf(message.get("from")));
            item.addProperty("text", String.valueOf(message.get("text")));
            messages.add(item);
        }
        context.add("inbox", messages);
        JsonObject job = new JsonObject();
        String path = jobPath(agent);
        if (getConfig().contains(path + ".kind")) {
            job.addProperty("kind", getConfig().getString(path + ".kind", ""));
            job.addProperty("status", getConfig().getString(path + ".status", ""));
            job.addProperty("progress", getConfig().getInt(path + ".progress", 0));
            if (getConfig().contains(path + ".target")) job.addProperty("target", getConfig().getInt(path + ".target"));
            if (getConfig().contains(path + ".length")) job.addProperty("length", getConfig().getInt(path + ".length"));
            if (getConfig().contains(path + ".start_y")) job.addProperty("y_level", getConfig().getInt(path + ".start_y"));
            context.add("current_job", job);
        } else {
            context.add("current_job", new JsonObject());
        }
        JsonObject supplies = new JsonObject();
        var supplySection = getConfig().getConfigurationSection(ownerSupplyPath(player.getUniqueId()));
        if (supplySection != null) {
            for (String material : supplySection.getKeys(false)) {
                int count = supplySection.getInt(material, 0);
                if (count > 0) supplies.addProperty(material, count);
            }
        }
        context.add("shared_supplies", supplies);
        return context;
    }

    private String statePath(Mob agent) {
        return "agents." + agent.getUniqueId();
    }

    private List<Mob> ownedAgents(Player owner) {
        List<Mob> agents = new ArrayList<>();
        String ownerId = owner.getUniqueId().toString();
        for (World world : Bukkit.getWorlds()) {
            for (Mob agent : world.getEntitiesByClass(Mob.class)) {
                if (!agent.getPersistentDataContainer().has(agentNameKey, PersistentDataType.STRING)) continue;
                String storedOwner = agent.getPersistentDataContainer().get(agentOwnerKey, PersistentDataType.STRING);
                if (ownerId.equals(storedOwner)) agents.add(agent);
            }
        }
        agents.sort(Comparator.comparing(this::agentName, String.CASE_INSENSITIVE_ORDER));
        return agents;
    }

    private boolean ownsAgent(Player owner, Mob agent) {
        String storedOwner = agent.getPersistentDataContainer().get(agentOwnerKey, PersistentDataType.STRING);
        if (storedOwner == null || storedOwner.isBlank()) {
            agent.getPersistentDataContainer().set(agentOwnerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            return true;
        }
        return storedOwner.equals(owner.getUniqueId().toString());
    }

    private List<Map<String, Object>> readInbox(Mob agent) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Map<?, ?> raw : getConfig().getMapList(statePath(agent) + ".inbox")) {
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            Object id = raw.get("id");
            Object from = raw.get("from");
            Object text = raw.get("text");
            if (id instanceof String && from instanceof String && text instanceof String) {
                message.put("id", clean((String) id, 40));
                message.put("from", clean((String) from, 24));
                message.put("text", clean((String) text, 120));
                messages.add(message);
            }
        }
        return messages;
    }

    private void consumeInboxSnapshot(Mob agent, Set<String> capturedIds) {
        if (capturedIds.isEmpty()) return;
        List<Map<String, Object>> remaining = readInbox(agent).stream()
                .filter(message -> !capturedIds.contains(String.valueOf(message.get("id"))))
                .toList();
        getConfig().set(statePath(agent) + ".inbox", remaining);
        saveConfig();
    }

    private void remember(Mob agent, String note) {
        String path = statePath(agent) + ".memory";
        List<String> memory = new ArrayList<>(getConfig().getStringList(path));
        memory.removeIf(existing -> existing.equalsIgnoreCase(note));
        memory.add(note);
        if (memory.size() > 8) memory = new ArrayList<>(memory.subList(memory.size() - 8, memory.size()));
        getConfig().set(path, memory);
        saveConfig();
    }

    private void sendAgentMessage(Player player, Mob sender, String recipient, String text) {
        Mob target = findAgent(player, recipient);
        if (target == null || target.equals(sender)) {
            player.sendMessage("§b[GeneCraft] §fI couldn't find another owned agent named " + clean(recipient, 24) + " nearby.");
            return;
        }
        if (!sender.getWorld().equals(target.getWorld()) || sender.getLocation().distance(target.getLocation()) > 16.0) {
            player.sendMessage("§b[GeneCraft] §fAgents can only pass messages within 16 blocks in the same world.");
            return;
        }
        String path = statePath(target) + ".inbox";
        List<Map<String, Object>> messages = new ArrayList<>(readInbox(target));
        messages.add(Map.of("id", UUID.randomUUID().toString(), "from", agentName(sender), "text", clean(text, 120)));
        if (messages.size() > 8) messages = new ArrayList<>(messages.subList(messages.size() - 8, messages.size()));
        getConfig().set(path, messages);
        saveConfig();
        player.sendMessage("§d[" + agentName(sender) + " → " + agentName(target) + "] §f" + clean(text, 120));
        boolean listening = getConfig().getBoolean(statePath(target) + ".autonomy.enabled", false);
        player.sendMessage("§b[GeneCraft] §fMessage saved in " + agentName(target) + "'s inbox"
                + (listening ? "; they will consider it on their next observation." : ". Use /genecraft inbox " + agentName(target) + " to review it."));
    }

    private void tickAutonomousAgents() {
        long now = System.currentTimeMillis();
        for (Player owner : Bukkit.getOnlinePlayers()) {
            for (Mob agent : ownedAgents(owner)) {
                String path = statePath(agent) + ".autonomy";
                if (!getConfig().getBoolean(path + ".enabled", false)
                        || !agent.getWorld().equals(owner.getWorld())
                        || agent.getLocation().distance(owner.getLocation()) > PLAYER_ACTION_LIMIT
                        || now < getConfig().getLong(path + ".nextRunAt", 0L)
                        || pendingPlans.contains(agent.getUniqueId())) continue;
                if (!reserveAutonomyCall()) {
                    getConfig().set(path + ".nextRunAt", now + 60_000L);
                    saveConfig();
                    continue;
                }
                int interval = Math.max(30, Math.min(1800, getConfig().getInt(path + ".intervalSeconds", 90)));
                getConfig().set(path + ".nextRunAt", now + interval * 1000L);
                saveConfig();
                requestPlan(owner, agent,
                        "Observe the current world and take one small, safe step toward your saved goal. Use recent memory and any waiting messages. Do not repeat the same message or action without a reason.",
                        true);
            }
        }
    }

    private boolean reserveAutonomyCall() {
        long hour = System.currentTimeMillis() / 3_600_000L;
        if (getConfig().getLong("autonomyBudget.hour", -1L) != hour) {
            getConfig().set("autonomyBudget.hour", hour);
            getConfig().set("autonomyBudget.calls", 0);
        }
        int calls = getConfig().getInt("autonomyBudget.calls", 0);
        if (calls >= 60) return false;
        getConfig().set("autonomyBudget.calls", calls + 1);
        saveConfig();
        return true;
    }

    private JsonObject position(Location location) {
        JsonObject position = new JsonObject();
        position.addProperty("x", Math.round(location.getX() * 10.0) / 10.0);
        position.addProperty("y", Math.round(location.getY() * 10.0) / 10.0);
        position.addProperty("z", Math.round(location.getZ() * 10.0) / 10.0);
        return position;
    }

    private Location waypointLocation(String waypoint) {
        String path = "waypoints." + waypoint;
        String worldName = getConfig().getString(path + ".world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || !getConfig().contains(path + ".x")) return null;
        return new Location(world, getConfig().getDouble(path + ".x"), getConfig().getDouble(path + ".y"), getConfig().getDouble(path + ".z"));
    }

    private void beginFollow(Player player, Mob agent) {
        UUID id = agent.getUniqueId();
        cancelFollow(id);
        int[] elapsedSeconds = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || !agent.isValid()) {
                cancelFollow(id);
                return;
            }
            if (!agent.getWorld().equals(player.getWorld()) || agent.getLocation().distance(player.getLocation()) > PLAYER_ACTION_LIMIT) {
                agent.getPathfinder().stopPathfinding();
                cancelFollow(id);
                player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " stopped following because you left the 32-block demo area.");
                return;
            }
            if (++elapsedSeconds[0] > 60) {
                agent.getPathfinder().stopPathfinding();
                cancelFollow(id);
                player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " finished its 60-second follow window.");
                return;
            }
            if (agent.getLocation().distanceSquared(player.getLocation()) <= 6.25) {
                agent.getPathfinder().stopPathfinding();
            } else {
                agent.getPathfinder().moveTo(player.getLocation(), 1.0);
            }
        }, 0L, 20L);
        followTasks.put(id, task);
        player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " is following you for up to 60 seconds. Use /genecraft stop " + agentName(agent) + " to stop sooner.");
    }

    private void cancelFollow(UUID agentId) {
        BukkitTask task = followTasks.remove(agentId);
        if (task != null) task.cancel();
    }

    private Mob findAgent(Player player, String name) {
        return player.getWorld().getEntitiesByClass(Mob.class).stream()
                .filter(v -> v.getPersistentDataContainer().has(agentNameKey, PersistentDataType.STRING))
                .filter(v -> name.equalsIgnoreCase(agentName(v)))
                .filter(v -> ownsAgent(player, v))
                .filter(v -> v.getLocation().distance(player.getLocation()) <= 48.0)
                // If older worlds contain duplicate names, route commands to
                // the entity that owns the saved work order before choosing
                // the nearest idle copy. This keeps /job status/cancel and
                // natural-language requests aligned with the active worker.
                .min(Comparator.<Mob, Boolean>comparing(v -> !activeJob(v))
                        .thenComparingDouble(v -> v.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    private Mob findAgentWithSavedJob(Player player, String name) {
        return ownedAgents(player).stream()
                .filter(v -> name.equalsIgnoreCase(agentName(v)))
                .filter(v -> !getConfig().getString(jobPath(v) + ".kind", "").isBlank()
                        && !getConfig().getString(jobPath(v) + ".status", "").isBlank())
                .min(Comparator.<Mob, Boolean>comparing(v -> !activeJob(v))
                        .thenComparingDouble(v -> v.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    private String agentName(Mob agent) {
        return agent.getPersistentDataContainer().getOrDefault(agentNameKey, PersistentDataType.STRING, "agent");
    }

    private void agentSay(Player player, String name, String text) {
        player.sendMessage("§d[" + clean(name, 24) + "] §f" + text);
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_YES, 0.5f, 1.2f);
    }

    private void bridgePost(CommandSender sender, String path, JsonObject body, String initial) {
        sender.sendMessage("§d[GeneCraft] §f" + initial);
        bridgeRequest("POST", path, body).whenComplete((response, error) -> Bukkit.getScheduler().runTask(this, () ->
                sender.sendMessage(error == null ? "§d[GeneCraft] §f" + bridgeMessage(response) : "§c[GeneCraft] §f" + friendlyBridgeError(error))));
    }

    private void bridgeGet(CommandSender sender, String path, String initial) {
        sender.sendMessage("§d[GeneCraft] §f" + initial);
        bridgeRequest("GET", path, null).whenComplete((response, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("§c[GeneCraft] §f" + friendlyBridgeError(error));
                return;
            }
            JsonObject result = parseObject(response);
            if (result == null) {
                sender.sendMessage("§c[GeneCraft] §fThe local AI bridge returned an unreadable response.");
            } else if (path.equals("/health")) {
                String status = result.has("status") ? result.get("status").getAsString() : "Unknown status";
                String model = result.has("model") ? result.get("model").getAsString() : "";
                boolean signedIn = result.has("signed_in") && result.get("signed_in").getAsBoolean();
                sender.sendMessage("§d[GeneCraft] §f" + status + (model.isBlank() ? "" : " · model: " + clean(model, 100)));
                if (signedIn && !getConfig().getBoolean("chatgptPlanWelcomeShown", false)) {
                    getConfig().set("chatgptPlanWelcomeShown", true);
                    saveConfig();
                    sendChatGptPlanMessage(sender, true);
                } else if (!signedIn) {
                    getConfig().set("chatgptPlanWelcomeShown", false);
                    saveConfig();
                }
            } else if (path.equals("/models")) {
                JsonArray models = result.has("models") && result.get("models").isJsonArray() ? result.getAsJsonArray("models") : new JsonArray();
                if (models.isEmpty()) {
                    sender.sendMessage("§d[GeneCraft] §fNo models were returned for this account.");
                } else {
                    sender.sendMessage("§d[GeneCraft] §fModels available to this account:");
                    for (int i = 0; i < Math.min(models.size(), 24); i++) {
                        JsonObject model = models.get(i).getAsJsonObject();
                        String slug = model.has("slug") ? model.get("slug").getAsString() : "";
                        String display = model.has("display_name") ? model.get("display_name").getAsString() : slug;
                        sender.sendMessage("§7- §f" + clean(display, 80) + " §8(" + clean(slug, 100) + ")");
                    }
                    if (models.size() > 24) sender.sendMessage("§7Showing the first 24. Use /genecraft model <model-id> to select one.");
                }
            } else {
                sender.sendMessage("§d[GeneCraft] §f" + clean(response, 350));
            }
        }));
    }

    private CompletableFuture<String> bridgeRequest(String method, String path, JsonObject body) {
        Path tokenPath = bridgeTokenPath();
        final String token;
        try {
            token = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(new IllegalStateException("Start the GeneCraft bridge first. See the README's 'Start the local bridge' section."));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(BRIDGE.resolve(path))
                .timeout(Duration.ofSeconds(100))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
        } else {
            request.GET();
        }
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("bridge returned HTTP " + response.statusCode() + ": " + response.body());
                    }
                    return response.body();
                });
    }

    private Path bridgeTokenPath() {
        String configured = System.getenv("GENECRAFT_HOME");
        if (configured == null || configured.isBlank()) configured = System.getenv("FABLEORBIT_HOME");
        Path directory;
        if (configured != null && !configured.isBlank()) {
            directory = Path.of(configured).toAbsolutePath();
        } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            if (appData == null || appData.isBlank()) appData = System.getenv("APPDATA");
            directory = appData == null || appData.isBlank()
                    ? Path.of(System.getProperty("user.home"), "AppData", "Local", "GeneCraft")
                    : Path.of(appData, "GeneCraft");
        } else {
            directory = Path.of(System.getProperty("user.home"), ".config", "genecraft");
        }
        return directory.resolve("bridge.token");
    }

    private JsonObject parseObject(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String friendlyBridgeError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage() == null ? "Local AI request failed." : cause.getMessage();
        int jsonStart = message.indexOf("{\"error\"");
        if (jsonStart >= 0) {
            JsonObject parsed = parseObject(message.substring(jsonStart));
            if (parsed != null && parsed.has("error")) message = message.substring(0, jsonStart).trim() + " " + parsed.get("error").getAsString();
        }
        if (message.contains("Start the GeneCraft bridge")) return message;
        if (message.contains("ConnectException") || message.contains("Connection refused")) return "Start the local GeneCraft bridge first. See the README tutorial.";
        return clean(message, 220);
    }

    private String bridgeMessage(String response) {
        JsonObject result = parseObject(response);
        return result != null && result.has("message") ? clean(result.get("message").getAsString(), 250) : clean(response, 250);
    }

    private void showChatGptLoginPrompt(CommandSender sender) {
        sender.sendMessage(Component.text("Optional: eligible requests can use your ChatGPT plan or credits. GeneCraft sends the request you type and limited nearby game context to OpenAI.")
                .color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Continue with ChatGPT")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/genecraft login continue"))
                .hoverEvent(HoverEvent.showText(Component.text("Open OpenAI sign-in and review the requested permissions."))));
        sender.sendMessage(Component.text("The model can only choose GeneCraft's bounded chat, movement, and work-order actions. The local pathfinding demo works without sign-in.")
                .color(NamedTextColor.GRAY));
    }

    private void sendChatGptPlanMessage(CommandSender sender, boolean welcome) {
        String message = welcome
                ? "You're using your ChatGPT plan. Eligible GeneCraft requests use your plan or credits; manage this app's usage in ChatGPT settings."
                : "Using your ChatGPT plan for this request. Manage this app's usage in ChatGPT settings.";
        Component prefix = Component.text(message + " ").color(NamedTextColor.GRAY);
        Component link = Component.text("Manage usage")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl("https://chatgpt.com/settings/usage"))
                .hoverEvent(HoverEvent.showText(Component.text("Open ChatGPT usage settings")));
        sender.sendMessage(prefix.append(link));
    }

    private String clean(String text, int maxLength) {
        String safe = text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replace('§', ' ');
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private boolean isWaypointName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,24}");
    }

    private boolean isAgentName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,24}");
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§bGeneCraft §7— local Minecraft agents");
        sender.sendMessage("§f/genecraft spawn <name> [form|skin <player>] §7create a visible agent");
        sender.sendMessage("§f/genecraft rename <old> <new> §7rename a nearby agent");
        sender.sendMessage("§f/genecraft demo <name> §7run safe pathfinding without AI sign-in");
        sender.sendMessage("§f/genecraft waypoint set <name> §7save your position");
        sender.sendMessage("§f@<agent> <message> §7chat naturally and give direct orders");
        sender.sendMessage("§f/genecraft ask <name> <request> §7ask the GPT agent to act");
        sender.sendMessage("§f/genecraft job <name> status|cancel §7check or cancel saved work");
        sender.sendMessage("§f/genecraft supplies §7view shared agent materials");
        sender.sendMessage("§f/genecraft stop <name> §7stop movement and cancel its work");
        sender.sendMessage("§f/genecraft remove <name> §7remove an agent and saved state");
        sender.sendMessage("§f/genecraft goal <name> set <goal> §7save a persistent goal");
        sender.sendMessage("§f/genecraft autonomy <name> on [seconds] §7enable bounded observation");
        sender.sendMessage("§f/genecraft inbox <name> §7read agent-to-agent messages");
        sender.sendMessage("§f/genecraft bodyguard [on|off|status] §7summon or dismiss token-free Bodyguard Bill");
        sender.sendMessage("§f/genecraft login §7show the Continue with ChatGPT option");
        sender.sendMessage("§f/genecraft logout | status | models | model <id> §7manage ChatGPT and model selection");
    }
}
