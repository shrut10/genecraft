package dev.genecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
    private static final String PERSISTENT_NAMESPACE = "fableorbit";

    private NamespacedKey agentNameKey;
    private NamespacedKey guardOwnerKey;
    private NamespacedKey agentOwnerKey;
    private NamespacedKey agentSkinKey;
    private NamespacedKey agentFormKey;
    private HttpClient http;
    private final Set<UUID> pendingPlans = ConcurrentHashMap.newKeySet();
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
        Bukkit.getScheduler().runTask(this, this::relabelExistingAgents);
        getLogger().info("GeneCraft ready. Use /genecraft help to start.");
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
            cancelFollow(agent.getUniqueId());
            agent.getPathfinder().stopPathfinding();
            player.sendMessage("§d[GeneCraft] §f" + agentName(agent) + " stopped.");
        }
        return true;
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
        cancelFollow(id);
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
                if (!agent.getWorld().equals(player.getWorld()) || agent.getLocation().distance(player.getLocation()) > PLAYER_ACTION_LIMIT) {
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
                } else {
                    cancelFollow(agent.getUniqueId());
                    boolean started = agent.getPathfinder().moveTo(destination, 1.0);
                    player.sendMessage(started ? "§d[GeneCraft] §f" + name + " is heading to '" + waypoint + "'."
                            : "§d[GeneCraft] §fI couldn't find a path to that waypoint.");
                }
            }
            case "stop_moving" -> {
                cancelFollow(agent.getUniqueId());
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
            case "follow_player", "stop_moving" -> arguments.isEmpty();
            default -> false;
        };
    }

    private JsonObject context(Player player, Mob agent) {
        JsonObject context = new JsonObject();
        context.addProperty("world", agent.getWorld().getName());
        context.add("agent_position", position(agent.getLocation()));
        context.addProperty("agent_health", agent.getHealth());
        context.add("player_position", position(player.getLocation()));
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
                .min(Comparator.comparingDouble(v -> v.getLocation().distanceSquared(player.getLocation())))
                .filter(v -> v.getLocation().distance(player.getLocation()) <= 48.0)
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
        sender.sendMessage(Component.text("The model can only choose GeneCraft's six bounded actions. The local pathfinding demo works without sign-in.")
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
        sender.sendMessage("§f/genecraft ask <name> <request> §7ask the GPT agent to act");
        sender.sendMessage("§f/genecraft stop <name> §7stop its movement");
        sender.sendMessage("§f/genecraft remove <name> §7remove an agent and saved state");
        sender.sendMessage("§f/genecraft goal <name> set <goal> §7save a persistent goal");
        sender.sendMessage("§f/genecraft autonomy <name> on [seconds] §7enable bounded observation");
        sender.sendMessage("§f/genecraft inbox <name> §7read agent-to-agent messages");
        sender.sendMessage("§f/genecraft bodyguard [on|off|status] §7summon or dismiss token-free Bodyguard Bill");
        sender.sendMessage("§f/genecraft login §7show the Continue with ChatGPT option");
        sender.sendMessage("§f/genecraft logout | status | models | model <id> §7manage ChatGPT and model selection");
    }
}
