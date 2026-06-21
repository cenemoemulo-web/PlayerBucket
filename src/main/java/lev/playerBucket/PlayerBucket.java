package lev.playerBucket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

public class PlayerBucket extends JavaPlugin implements Listener, CommandExecutor {
    private static final long DEFAULT_RELEASE_INPUT_COOLDOWN_MS = 500L;

    private final Map<UUID, UUID> caughtToCatcher = new HashMap<>();
    private final Map<UUID, UUID> catcherToCaught = new HashMap<>();
    private final Map<UUID, Long> catchTime = new HashMap<>();
    private final Map<UUID, BukkitTask> freezeTasks = new HashMap<>();
    private final Map<UUID, Location> frozenAt = new HashMap<>();
    private final Map<UUID, String> mathAnswers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> droppedBuckets = new HashMap<>();
    private final Map<UUID, Long> releaseInputBlockedUntil = new HashMap<>();
    private final Map<UUID, GameMode> originalGameModes = new HashMap<>();
    private final Map<UUID, Location> containerReleaseLocations = new HashMap<>();
    private final Map<UUID, Location> containerBucketLocations = new HashMap<>();
    private final Map<CombatPair, Long> pvpZones = new HashMap<>();

    private NamespacedKey caughtUuidKey;
    private long escapeCooldownMs;
    private long releaseInputCooldownMs;
    private long pvpBlockMillis;
    private double pvpZoneRadius;
    private boolean allowCatchingOps;

    @Override
    public void onEnable() {
        caughtUuidKey = new NamespacedKey(this, "caught_uuid");
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("escape") != null) {
            getCommand("escape").setExecutor(this);
        }

        getLogger().info("PlayerBucket enabled!");
    }

    @Override
    public void onDisable() {
        for (UUID caughtId : new HashSet<>(caughtToCatcher.keySet())) {
            Player caught = Bukkit.getPlayer(caughtId);
            releaseCaughtPlayer(caughtId, caught == null ? null : caught.getLocation());
        }
    }

    private void loadConfigValues() {
        reloadConfig();
        escapeCooldownMs = getConfig().getLong("escape_cooldown_seconds", 10L) * 1000L;
        releaseInputCooldownMs = getConfig().getLong(
                "release_input_cooldown_millis", DEFAULT_RELEASE_INPUT_COOLDOWN_MS);
        pvpBlockMillis = getConfig().getLong("pvp_bucket_block_seconds", 15L) * 1000L;
        pvpZoneRadius = getConfig().getDouble("pvp_bucket_zone_radius", 12.0D);
        allowCatchingOps = getConfig().getBoolean("allow_catching_ops", true);
    }

    private String msg(String key, String... replacements) {
        String message = getConfig().getString("messages." + key, key);
        message = ChatColor.translateAlternateColorCodes('&', message);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        return message;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }

        UUID playerId = player.getUniqueId();
        player.sendMessage(msg(caughtToCatcher.containsKey(playerId) ? "answer_in_chat" : "escape_not_caught"));
        return true;
    }

    private void showMathProblem(Player player, UUID playerId) {
        String[] problem = generateMathProblem();
        mathAnswers.put(playerId, problem[1]);
        player.sendMessage(msg("escape_problem", "%problem%", problem[0]));
        player.sendMessage(msg("answer_in_chat"));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String expectedAnswer = mathAnswers.get(playerId);
        if (expectedAnswer == null) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!message.equals(mathAnswers.get(playerId))) {
                player.sendMessage(msg("math_wrong"));
                return;
            }

            mathAnswers.remove(playerId);
            UUID catcherId = caughtToCatcher.get(playerId);
            Location releaseLocation = getStoredReleaseLocation(playerId, player.getLocation());
            releaseCaughtPlayer(playerId, releaseLocation);
            player.sendMessage(msg("escaped_victim"));

            Player catcher = catcherId == null ? null : Bukkit.getPlayer(catcherId);
            if (catcher != null) {
                catcher.sendMessage(msg("escaped_catcher", "%player%", player.getName()));
            }
        });
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Player caught)) {
            return;
        }

        Player catcher = event.getPlayer();
        ItemStack item = catcher.getInventory().getItem(event.getHand());
        if (item == null || item.getType() != Material.WATER_BUCKET) {
            return;
        }

        event.setCancelled(true);
        long remaining = getPvpZoneRemainingSeconds(catcher.getLocation(), caught.getLocation());
        if (remaining > 0L) {
            catcher.sendMessage(msg("pvp_blocked", "%remaining%", String.valueOf(remaining)));
            return;
        }
        if (!allowCatchingOps && caught.isOp()) {
            catcher.sendMessage(msg("op_immune", "%player%", caught.getName()));
            return;
        }

        UUID catcherId = catcher.getUniqueId();
        UUID caughtId = caught.getUniqueId();
        if (caughtToCatcher.containsKey(caughtId)) {
            catcher.sendMessage(msg("already_caught", "%player%", caught.getName()));
            return;
        }
        if (catcherToCaught.containsKey(catcherId)) {
            catcher.sendMessage(msg("already_holding"));
            return;
        }

        Location caughtLocation = caught.getLocation().clone();
        caughtToCatcher.put(caughtId, catcherId);
        catcherToCaught.put(catcherId, caughtId);
        catchTime.put(caughtId, System.currentTimeMillis());
        frozenAt.put(caughtId, caughtLocation);
        releaseInputBlockedUntil.put(catcherId, System.currentTimeMillis() + releaseInputCooldownMs);
        originalGameModes.put(caughtId, caught.getGameMode());

        item.setAmount(item.getAmount() - 1);
        Map<Integer, ItemStack> overflow = catcher.getInventory().addItem(createBucketItem(caught.getName(), caughtId));
        overflow.values().forEach(leftover -> {
            Item dropped = catcher.getWorld().dropItemNaturally(catcher.getLocation(), leftover);
            droppedBuckets.put(dropped.getUniqueId(), caughtId);
        });

        catcher.playSound(catcher.getLocation(), Sound.ITEM_BUCKET_FILL, 1.0F, 1.0F);
        BukkitTask task = getServer().getScheduler().runTaskTimer(this, () -> {
            Player caughtPlayer = Bukkit.getPlayer(caughtId);
            if (caughtPlayer != null && caughtToCatcher.containsKey(caughtId)) {
                caughtPlayer.setSpectatorTarget(catcher);
            }
        }, 0L, 1L);
        freezeTasks.put(caughtId, task);
        caught.setGameMode(GameMode.SPECTATOR);
        caught.setSpectatorTarget(catcher);

        catcher.sendMessage(msg("caught_catcher", "%player%", caught.getName()));
        caught.sendMessage(msg("caught_victim", "%catcher%", catcher.getName()));
        showMathProblem(caught, caughtId);
    }

    @EventHandler
    public void onPlayerDamagePlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player damaged)) {
            return;
        }

        Player damager = getDamagingPlayer(event.getDamager());
        if (damager == null || damager.equals(damaged)) {
            return;
        }

        long now = System.currentTimeMillis();
        pvpZones.put(new CombatPair(damaged.getUniqueId(), damager.getUniqueId()), now + pvpBlockMillis);
    }

    private Player getDamagingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (!(damager instanceof Projectile projectile)) {
            return null;
        }

        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof Player player ? player : null;
    }

    private long getPvpZoneRemainingSeconds(Location catcherLocation, Location caughtLocation) {
        long now = System.currentTimeMillis();
        long remainingMs = 0L;
        pvpZones.entrySet().removeIf(entry -> entry.getValue() <= now);

        for (Map.Entry<CombatPair, Long> entry : pvpZones.entrySet()) {
            CombatPair pair = entry.getKey();
            Player first = Bukkit.getPlayer(pair.first());
            Player second = Bukkit.getPlayer(pair.second());
            if (first == null || second == null) {
                continue;
            }

            if (isInsidePvpZone(catcherLocation, first, second) || isInsidePvpZone(caughtLocation, first, second)) {
                remainingMs = Math.max(remainingMs, entry.getValue() - now);
            }
        }

        return remainingMs <= 0L ? 0L : (remainingMs + 999L) / 1000L;
    }

    private boolean isInsidePvpZone(Location location, Player first, Player second) {
        return isWithinRadius(location, first.getLocation(), pvpZoneRadius)
                || isWithinRadius(location, second.getLocation(), pvpZoneRadius);
    }

    private boolean isWithinRadius(Location location, Location center, double radius) {
        if (location == null
                || center == null
                || location.getWorld() == null
                || center.getWorld() == null
                || !location.getWorld().equals(center.getWorld())) {
            return false;
        }

        return location.distanceSquared(center) <= radius * radius;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player catcher = event.getPlayer();
        UUID catcherId = catcher.getUniqueId();
        if (!catcherToCaught.containsKey(catcherId)) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isBucketItem(item)) {
            return;
        }

        Long blockedUntil = releaseInputBlockedUntil.get(catcherId);
        if (blockedUntil != null && blockedUntil > System.currentTimeMillis()) {
            event.setCancelled(true);
            return;
        }
        releaseInputBlockedUntil.remove(catcherId);

        event.setCancelled(true);
        UUID caughtId = getCaughtId(item);
        if (caughtId == null || !caughtId.equals(catcherToCaught.get(catcherId))) {
            caughtId = catcherToCaught.get(catcherId);
        }

        Location releaseLocation = getReleaseLocation(event, catcher);
        resetBucketItem(item);

        Player caught = Bukkit.getPlayer(caughtId);
        String caughtName = caught == null ? "player" : caught.getName();
        releaseCaughtPlayer(caughtId, releaseLocation);

        catcher.sendMessage(msg("released_catcher", "%player%", caughtName));
        catcher.playSound(catcher.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1.0F, 1.0F);
        if (caught != null) {
            caught.sendMessage(msg("released_victim"));
            caught.playSound(releaseLocation, Sound.ITEM_BUCKET_EMPTY, 1.0F, 1.0F);
        }
    }

    private Location getReleaseLocation(PlayerInteractEvent event, Player catcher) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            BlockFace face = event.getBlockFace();
            return findSafeReleaseLocation(event.getClickedBlock(), face, catcher.getLocation());
        }
        return findSafeReleaseLocation(catcher.getLocation());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity clicked = event.getWhoClicked();
        if (!(clicked instanceof Player player) || !catcherToCaught.containsKey(player.getUniqueId())) {
            return;
        }

        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory == null
                || topInventory.getType() == InventoryType.CRAFTING
                || topInventory.getType() == InventoryType.PLAYER) {
            return;
        }

        boolean placingBucket = false;
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && event.getClickedInventory() instanceof PlayerInventory
                && isBucketItem(event.getCurrentItem())) {
            placingBucket = true;
        }
        if (event.getClickedInventory() != null
                && event.getClickedInventory().equals(topInventory)
                && isBucketItem(event.getCursor())) {
            placingBucket = true;
        }

        if (!placingBucket) {
            return;
        }

        UUID caughtId = catcherToCaught.get(player.getUniqueId());
        rememberContainerRelease(topInventory, caughtId);
        player.sendMessage(msg("stored_in_chest"));
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!catcherToCaught.containsKey(playerId) || !isBucketItem(event.getItemDrop().getItemStack())) {
            return;
        }
        droppedBuckets.put(event.getItemDrop().getUniqueId(), catcherToCaught.get(playerId));
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }

        UUID caughtId = droppedBuckets.remove(item.getUniqueId());
        if (caughtId == null) {
            return;
        }

        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.LAVA
                && cause != EntityDamageEvent.DamageCause.FIRE
                && cause != EntityDamageEvent.DamageCause.FIRE_TICK) {
            droppedBuckets.put(item.getUniqueId(), caughtId);
            return;
        }

        Player caught = Bukkit.getPlayer(caughtId);
        UUID catcherId = caughtToCatcher.get(caughtId);
        Location releaseLocation = item.getLocation();
        releaseCaughtPlayer(caughtId, releaseLocation);

        if (caught != null) {
            caught.sendMessage(msg("released_lava"));
        }

        Player catcher = catcherId == null ? null : Bukkit.getPlayer(catcherId);
        if (catcher != null) {
            String name = caught == null ? "the player" : caught.getName();
            catcher.sendMessage(ChatColor.RED + name + "'s bucket burned in lava.");
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        UUID caughtId = droppedBuckets.remove(event.getItem().getUniqueId());
        if (caughtId == null) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            catcherToCaught.entrySet().removeIf(entry -> entry.getValue().equals(caughtId));
            catcherToCaught.put(player.getUniqueId(), caughtId);
            caughtToCatcher.put(caughtId, player.getUniqueId());
            releaseInputBlockedUntil.put(player.getUniqueId(), System.currentTimeMillis() + releaseInputCooldownMs);
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        UUID caughtId = droppedBuckets.remove(event.getEntity().getUniqueId());
        if (caughtId == null) {
            return;
        }

        Player caught = Bukkit.getPlayer(caughtId);
        releaseCaughtPlayer(caughtId, findSafeReleaseLocation(event.getEntity().getLocation()));
        if (caught != null) {
            caught.sendMessage(msg("released_despawn"));
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (UUID caughtId : new HashSet<>(frozenAt.keySet())) {
            Location location = frozenAt.get(caughtId);
            if (!isInChunk(location, chunk)) {
                continue;
            }

            Player caught = Bukkit.getPlayer(caughtId);
            releaseCaughtPlayer(caughtId, caught == null ? location : caught.getLocation());
            if (caught != null) {
                caught.sendMessage(msg("released_chunk"));
            }
        }

        for (UUID caughtId : new HashSet<>(containerBucketLocations.keySet())) {
            Location location = containerBucketLocations.get(caughtId);
            if (!isInChunk(location, chunk)) {
                continue;
            }

            Player caught = Bukkit.getPlayer(caughtId);
            releaseCaughtPlayer(caughtId, findSafeReleaseLocationNearBlock(location.getBlock()));
            if (caught != null) {
                caught.sendMessage(msg("released_chunk"));
            }
        }

        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Item item)) {
                continue;
            }

            UUID caughtId = droppedBuckets.get(item.getUniqueId());
            if (caughtId == null) {
                continue;
            }

            Player caught = Bukkit.getPlayer(caughtId);
            releaseCaughtPlayer(caughtId, item.getLocation());
            if (caught != null) {
                caught.sendMessage(msg("released_chunk"));
            }
        }
    }

    private boolean isInChunk(Location location, Chunk chunk) {
        return location != null
                && location.getWorld() != null
                && location.getWorld().equals(chunk.getWorld())
                && (location.getBlockX() >> 4) == chunk.getX()
                && (location.getBlockZ() >> 4) == chunk.getZ();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (caughtToCatcher.containsKey(playerId)) {
            releaseCaughtPlayer(playerId, event.getPlayer().getLocation());
        }
        if (catcherToCaught.containsKey(playerId)) {
            UUID caughtId = catcherToCaught.get(playerId);
            Player caught = Bukkit.getPlayer(caughtId);
            releaseCaughtPlayer(caughtId, caught == null ? event.getPlayer().getLocation() : caught.getLocation());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        if (caughtToCatcher.containsKey(playerId)) {
            releaseCaughtPlayer(playerId, event.getEntity().getLocation());
        }
        if (catcherToCaught.containsKey(playerId)) {
            UUID caughtId = catcherToCaught.get(playerId);
            Player caught = Bukkit.getPlayer(caughtId);
            releaseCaughtPlayer(caughtId, caught == null ? event.getEntity().getLocation() : caught.getLocation());
        }
    }

    private void releaseCaughtPlayer(UUID caughtId, Location releaseLocation) {
        GameMode originalGameMode = originalGameModes.remove(caughtId);
        cleanupBucketItems(caughtId);
        caughtToCatcher.remove(caughtId);
        catchTime.remove(caughtId);
        frozenAt.remove(caughtId);
        mathAnswers.remove(caughtId);
        containerReleaseLocations.remove(caughtId);
        containerBucketLocations.remove(caughtId);
        droppedBuckets.entrySet().removeIf(entry -> entry.getValue().equals(caughtId));

        BukkitTask task = freezeTasks.remove(caughtId);
        if (task != null) {
            task.cancel();
        }

        catcherToCaught.entrySet().removeIf(entry -> entry.getValue().equals(caughtId));
        releaseInputBlockedUntil.entrySet().removeIf(entry -> !catcherToCaught.containsKey(entry.getKey()));

        Player caught = Bukkit.getPlayer(caughtId);
        if (caught != null && releaseLocation != null) {
            caught.setSpectatorTarget(null);
            caught.setGameMode(originalGameMode == null ? GameMode.SURVIVAL : originalGameMode);
            caught.teleport(findSafeReleaseLocation(releaseLocation));
        }
    }

    private Location getStoredReleaseLocation(UUID caughtId, Location fallback) {
        Location containerRelease = containerReleaseLocations.get(caughtId);
        if (containerRelease != null) {
            return containerRelease;
        }
        return frozenAt.getOrDefault(caughtId, fallback);
    }

    private void rememberContainerRelease(Inventory inventory, UUID caughtId) {
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof BlockState blockState)) {
            return;
        }

        Location blockLocation = blockState.getLocation();
        containerBucketLocations.put(caughtId, blockLocation.clone());
        containerReleaseLocations.put(caughtId, findSafeReleaseLocationNearBlock(blockLocation.getBlock()));
    }

    private void cleanupBucketItems(UUID caughtId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (caughtId.equals(getCaughtId(item))) {
                    resetBucketItem(item);
                }
            }
            for (ItemStack item : player.getInventory().getArmorContents()) {
                if (caughtId.equals(getCaughtId(item))) {
                    resetBucketItem(item);
                }
            }
            if (caughtId.equals(getCaughtId(player.getItemOnCursor()))) {
                resetBucketItem(player.getItemOnCursor());
            }
            player.updateInventory();
        }

        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                ItemStack stack = item.getItemStack();
                if (caughtId.equals(getCaughtId(stack))) {
                    resetBucketItem(stack);
                    item.setItemStack(stack);
                    droppedBuckets.remove(item.getUniqueId());
                }
            }
        }

        Location containerLocation = containerBucketLocations.get(caughtId);
        if (containerLocation != null && containerLocation.getWorld() != null) {
            BlockState state = containerLocation.getBlock().getState();
            if (state instanceof InventoryHolder holder) {
                Inventory inventory = holder.getInventory();
                for (int slot = 0; slot < inventory.getSize(); slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (caughtId.equals(getCaughtId(stack))) {
                        resetBucketItem(stack);
                        inventory.setItem(slot, stack);
                    }
                }
            }
        }
    }

    private Location findSafeReleaseLocationNearBlock(Block block) {
        BlockFace[] faces = {
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
        };
        for (BlockFace face : faces) {
            Location location = block.getRelative(face).getLocation().add(0.5D, 0.0D, 0.5D);
            if (isSafeForPlayer(location)) {
                return location;
            }
        }
        return findSafeReleaseLocation(block.getLocation().add(0.5D, 1.0D, 0.5D));
    }

    private Location findSafeReleaseLocation(Block clickedBlock, BlockFace face, Location fallback) {
        Block target = clickedBlock.getRelative(face);
        Location preferred = target.getLocation().add(0.5D, 0.0D, 0.5D);
        preferred.setYaw(fallback.getYaw());
        preferred.setPitch(fallback.getPitch());
        return findSafeReleaseLocation(preferred);
    }

    private Location findSafeReleaseLocation(Location preferred) {
        if (preferred == null || preferred.getWorld() == null) {
            return preferred;
        }

        Location centered = preferred.clone();
        centered.setX(centered.getBlockX() + 0.5D);
        centered.setZ(centered.getBlockZ() + 0.5D);
        if (isSafeForPlayer(centered)) {
            return centered;
        }

        for (int radius = 1; radius <= 12; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    for (int y = 3; y >= -3; y--) {
                        Location candidate = centered.clone().add(x, y, z);
                        candidate.setX(candidate.getBlockX() + 0.5D);
                        candidate.setZ(candidate.getBlockZ() + 0.5D);
                        if (isSafeForPlayer(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }

        World world = centered.getWorld();
        int safeY = world.getHighestBlockYAt(centered) + 1;
        Location surface = new Location(world, centered.getBlockX() + 0.5D, safeY, centered.getBlockZ() + 0.5D,
                centered.getYaw(), centered.getPitch());
        return isSafeForPlayer(surface) ? surface : centered;
    }

    private boolean isSafeForPlayer(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        return feet.isPassable()
                && head.isPassable()
                && ground.getType().isSolid()
                && !isDangerous(feet.getType())
                && !isDangerous(head.getType())
                && !isDangerous(ground.getType());
    }

    private boolean isDangerous(Material material) {
        return material == Material.LAVA
                || material == Material.FIRE
                || material == Material.SOUL_FIRE
                || material == Material.CACTUS
                || material == Material.MAGMA_BLOCK;
    }

    private String[] generateMathProblem() {
        Random random = new Random();
        return switch (random.nextInt(4)) {
            case 0 -> {
                int a = random.nextInt(18) + 7;
                int b = random.nextInt(13) + 8;
                int c = random.nextInt(40) + 20;
                int d = random.nextInt(16) + 5;
                yield new String[] {"(" + a + " x " + b + ") - " + c + " + " + d, String.valueOf((a * b) - c + d)};
            }
            case 1 -> {
                int answer = random.nextInt(31) - 15;
                int multiplier = random.nextInt(7) + 3;
                int addend = random.nextInt(50) - 25;
                int left = (answer * multiplier) + addend;
                yield new String[] {"(" + left + " - " + addend + ") / " + multiplier, String.valueOf(answer)};
            }
            case 2 -> {
                int coefficient = random.nextInt(5) + 2;
                int answer = random.nextInt(31) - 15;
                int addend = random.nextInt(41) - 20;
                yield new String[] {coefficient + "x + " + addend + " = " + ((coefficient * answer) + addend),
                        String.valueOf(answer)};
            }
            default -> {
                int root = random.nextInt(10) + 7;
                int offset = random.nextInt(31) - 15;
                int multiplier = random.nextInt(5) + 2;
                int answer = (root + offset) * multiplier;
                yield new String[] {"(sqrt(" + (root * root) + ") + " + offset + ") x " + multiplier,
                        String.valueOf(answer)};
            }
        };
    }

    private ItemStack createBucketItem(String playerName, UUID caughtId) {
        ItemStack bucket = new ItemStack(Material.PUFFERFISH_BUCKET);
        ItemMeta meta = bucket.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + playerName + " Bucket");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Contains: " + ChatColor.WHITE + playerName,
                    ChatColor.YELLOW + "Right-click to release.",
                    ChatColor.RED + "They can escape by solving the chat problem."));
            meta.getPersistentDataContainer().set(caughtUuidKey, PersistentDataType.STRING, caughtId.toString());
            bucket.setItemMeta(meta);
        }
        return bucket;
    }

    private boolean isBucketItem(ItemStack item) {
        if (item == null || item.getType() != Material.PUFFERFISH_BUCKET) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(caughtUuidKey, PersistentDataType.STRING);
    }

    private UUID getCaughtId(ItemStack item) {
        if (!isBucketItem(item)) {
            return null;
        }

        String rawUuid = item.getItemMeta().getPersistentDataContainer().get(caughtUuidKey, PersistentDataType.STRING);
        if (rawUuid == null) {
            return null;
        }

        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void resetBucketItem(ItemStack item) {
        if (item == null) {
            return;
        }
        item.setType(Material.WATER_BUCKET);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.setDisplayName(null);
        meta.setLore(null);
        meta.getPersistentDataContainer().remove(caughtUuidKey);
        item.setItemMeta(meta);
    }

    private record CombatPair(UUID first, UUID second) {
        private CombatPair {
            if (first.compareTo(second) > 0) {
                UUID originalFirst = first;
                first = second;
                second = originalFirst;
            }
        }
    }
}
