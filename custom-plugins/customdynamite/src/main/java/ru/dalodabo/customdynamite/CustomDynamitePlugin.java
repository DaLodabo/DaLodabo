package ru.dalodabo.customdynamite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CustomDynamitePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey tierKey;
    private NamespacedKey spawnerTypeKey;
    private NamespacedKey jakePickKey;
    private final Map<String, Integer> privateCoreHealth = new HashMap<>();
    private final Map<String, String> privateCoreOwner = new HashMap<>();
    private final Map<String, UUID> privateCoreHologram = new HashMap<>();
    private final Map<UUID, Long> goldenAppleCooldown = new HashMap<>();
    private final Map<UUID, Long> enchantedGoldenAppleCooldown = new HashMap<>();
    private File healthFile;

    private enum Tier {
        C("c", "&7Динамит C", 1.0f, "customdynamite.use.c"),
        B("b", "&eДинамит B", 3.0f, "customdynamite.use.b"),
        A("a", "&6Динамит A", 5.0f, "customdynamite.use.a"),
        TAERBLACK("taerblack", "&4Taerblack", 10.0f, "customdynamite.use.taerblack"),
        STILER("stiler", "&5Stiler", 10.0f, "customdynamite.use.stiler"),
        BLASTWAVE("blastwave", "&dРазрывная волна", 10.0f, "customdynamite.use.blastwave");

        final String id;
        final String display;
        final float multiplier;
        final String permission;

        Tier(String id, String display, float multiplier, String permission) {
            this.id = id;
            this.display = display;
            this.multiplier = multiplier;
            this.permission = permission;
        }

        static Tier fromId(String id) {
            for (Tier t : values()) {
                if (t.id.equalsIgnoreCase(id)) {
                    return t;
                }
            }
            return null;
        }

        boolean canBreak(Material material) {
            return switch (this) {
                case C -> material == Material.IRON_BLOCK;
                case B -> material == Material.IRON_BLOCK || material == Material.GOLD_BLOCK;
                case A -> material == Material.IRON_BLOCK || material == Material.GOLD_BLOCK || material == Material.DIAMOND_BLOCK;
                case TAERBLACK, BLASTWAVE -> material == Material.IRON_BLOCK
                        || material == Material.GOLD_BLOCK
                        || material == Material.DIAMOND_BLOCK
                        || material == Material.NETHERITE_BLOCK
                        || material == Material.OBSIDIAN;
                case STILER -> false;
            };
        }
    }

    @Override
    public void onEnable() {
        tierKey = new NamespacedKey(this, "tier");
        spawnerTypeKey = new NamespacedKey(this, "spawner_type");
        jakePickKey = new NamespacedKey(this, "jake_pick");
        healthFile = new File(getDataFolder(), "core-health.yml");
        loadHealthData();
        registerRecipes();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("cdyn") != null) {
            getCommand("cdyn").setExecutor(this);
            getCommand("cdyn").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTask(this, this::rebuildAllHolograms);
        getLogger().info("CustomDynamite enabled");
    }

    @Override
    public void onDisable() {
        removeAllHolograms();
        saveHealthData();
    }

    private void registerRecipes() {
        addTierRecipe(Tier.C, new ItemStack(Material.TNT));
        addTierRecipe(Tier.B, createDynamiteItem(Tier.C));
        addTierRecipe(Tier.A, createDynamiteItem(Tier.B));
        addTierRecipe(Tier.TAERBLACK, createDynamiteItem(Tier.A));
        addTierRecipe(Tier.STILER, createDynamiteItem(Tier.TAERBLACK));
    }

    private void addTierRecipe(Tier resultTier, ItemStack centerIngredient) {
        ItemStack result = createDynamiteItem(resultTier);
        NamespacedKey recipeKey = new NamespacedKey(this, "dynamite_" + resultTier.id);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, result);
        recipe.shape("SGS", "GCG", "SGS");
        recipe.setIngredient('S', Material.SAND);
        recipe.setIngredient('G', Material.GUNPOWDER);
        if (centerIngredient.getType() == Material.TNT && resultTier == Tier.C) {
            recipe.setIngredient('C', Material.TNT);
        } else {
            recipe.setIngredient('C', new RecipeChoice.ExactChoice(centerIngredient));
        }

        Bukkit.addRecipe(recipe);
    }

    private ItemStack createDynamiteItem(Tier tier) {
        ItemStack stack = new ItemStack(Material.TNT);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        meta.setDisplayName(color(tier.display));
        List<String> lore = new ArrayList<>();
        lore.add(color("&7Сила: &f" + tier.multiplier + "x от обычного TNT"));
        switch (tier) {
            case C -> lore.add(color("&7Ломает: &fжелезный приват"));
            case B -> lore.add(color("&7Ломает: &fжелезный, золотой приват"));
            case A -> lore.add(color("&7Ломает: &fжелезный, золотой, алмазный приват"));
            case TAERBLACK -> lore.add(color("&7Ломает: &fобсидиан, незеритовый приват и ниже"));
            case STILER -> lore.add(color("&7Взрывает спавнеры. Шанс 50% на дроп спавнера с мобом."));
            case BLASTWAVE -> lore.add(color("&7Ломает: &fвсё как Taerblack + под водой"));
        }
        meta.setLore(lore);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(tierKey, PersistentDataType.STRING, tier.id);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.TNT) {
            return;
        }

        Tier tier = readTier(item);
        if (tier == null) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission(tier.permission)) {
            player.sendMessage(color("&cУ тебя нет прав на использование этого динамита."));
            return;
        }

        event.setCancelled(true);
        consumeOne(player, item);

        TNTPrimed tnt = player.getWorld().spawn(player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.5)), TNTPrimed.class);
        tnt.setSource(player);
        tnt.setFuseTicks(60);
        tnt.setYield(4.0f * tier.multiplier);

        Vector velocity = player.getLocation().getDirection().normalize().multiply(1.2);
        velocity.setY(Math.max(0.2, velocity.getY()));
        tnt.setVelocity(velocity);

        tnt.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof TNTPrimed)) {
            return;
        }

        Tier tier = readTier(entity);
        if (tier == null) {
            return;
        }

        event.setRadius(4.0f * tier.multiplier);
        event.setFire(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        Tier tier = readTier(entity);

        // Ordinary explosions cannot break private core blocks or obsidian.
        if (tier == null) {
            event.blockList().removeIf(this::isProtectedCoreOrObsidian);
            return;
        }

        boolean blastwaveUnderwater = tier == Tier.BLASTWAVE && isUnderwater(entity.getLocation());

        if (blastwaveUnderwater) {
            List<Block> extra = collectNearbyBlocks(entity.getLocation(), event.getYield());
            event.blockList().addAll(extra);
        }

        if (tier == Tier.STILER) {
            processSpawnerDrops(event.blockList());
        }

        event.blockList().removeIf(block -> {
            Material type = block.getType();
            if (type == Material.AIR) {
                return true;
            }
            if (type == Material.BEDROCK || type == Material.BARRIER || type == Material.END_PORTAL_FRAME) {
                return true;
            }

            if (blastwaveUnderwater) {
                return false;
            }

            if (isProtectedCoreOrObsidian(block)) {
                if (isPrivateCore(type) && isOneDamageTier(tier)) {
                    boolean shouldBreak = applyPrivateCoreDamage(block);
                    if (!shouldBreak) {
                        updateCoreHologram(block);
                    } else {
                        cleanupCoreData(block);
                    }
                    return !shouldBreak;
                }
                if (isPrivateCore(type) && tier.canBreak(type)) {
                    cleanupCoreData(block);
                }
                return !tier.canBreak(type);
            }
            return false;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerPlace(BlockPlaceEvent event) {
        ItemStack stack = event.getItemInHand();
        if (stack == null || stack.getType() != Material.SPAWNER) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        String typeName = meta.getPersistentDataContainer().get(spawnerTypeKey, PersistentDataType.STRING);
        if (typeName == null || typeName.isEmpty()) {
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(typeName);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        BlockState state = event.getBlockPlaced().getState();
        if (!(state instanceof CreatureSpawner spawner)) {
            return;
        }
        spawner.setSpawnedType(type);
        spawner.update(true, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!isPlayerInCombat(player)) {
            return;
        }

        Material type = event.getItem().getType();
        if (type != Material.GOLDEN_APPLE && type != Material.ENCHANTED_GOLDEN_APPLE) {
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = (type == Material.GOLDEN_APPLE ? 7_000L : 15_000L);
        Map<UUID, Long> map = (type == Material.GOLDEN_APPLE ? goldenAppleCooldown : enchantedGoldenAppleCooldown);

        Long until = map.get(player.getUniqueId());
        if (until != null && until > now) {
            long leftSec = Math.max(1L, (long) Math.ceil((until - now) / 1000.0));
            event.setCancelled(true);
            player.sendMessage(color("&cCooldown: &f" + leftSec + "s &cfor this apple in PvP."));
            return;
        }

        map.put(player.getUniqueId(), now + cooldownMillis);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onJakePickBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isJakePick(hand)) {
            return;
        }

        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) {
            event.setCancelled(true);
            player.sendMessage(color("&cКирка Джейка ломает только спавнеры."));
            return;
        }

        BlockState state = block.getState();
        if (!(state instanceof CreatureSpawner spawner)) {
            event.setCancelled(true);
            return;
        }

        event.setDropItems(false);
        event.setExpToDrop(0);
        block.setType(Material.AIR, false);

        ItemStack drop = buildSpawnerDrop(spawner.getSpawnedType());
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);

        player.getInventory().setItemInMainHand(null);
        player.sendMessage(color("&aКирка Джейка сломалась после добычи спавнера."));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrivateCorePlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!isPrivateCore(block.getType())) {
            return;
        }
        String key = blockKey(block);
        privateCoreOwner.put(key, event.getPlayer().getName());
        // If block has never been damaged, keep health map empty and use max health by default.
        updateCoreHologram(block);
        saveHealthData();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakCleanup(org.bukkit.event.block.BlockBreakEvent event) {
        Material type = event.getBlock().getType();
        if (isPrivateCore(type)) {
            cleanupCoreData(event.getBlock());
        }
    }

    private void processSpawnerDrops(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() != Material.SPAWNER) {
                continue;
            }
            if (Math.random() > 0.5) {
                continue;
            }
            BlockState state = block.getState();
            if (!(state instanceof CreatureSpawner spawner)) {
                continue;
            }

            EntityType spawnType = spawner.getSpawnedType();
            ItemStack drop = buildSpawnerDrop(spawnType);

            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }

    private ItemStack buildSpawnerDrop(EntityType spawnType) {
        ItemStack drop = new ItemStack(Material.SPAWNER, 1);
        ItemMeta meta = drop.getItemMeta();
        if (meta == null) {
            return drop;
        }
        meta.setDisplayName(color("&dСпавнер &7(" + prettyEntity(spawnType) + "&7)"));
        meta.getPersistentDataContainer().set(spawnerTypeKey, PersistentDataType.STRING, spawnType.name());
        List<String> lore = new ArrayList<>();
        lore.add(color("&7Тип моба: &f" + prettyEntity(spawnType)));
        meta.setLore(lore);
        drop.setItemMeta(meta);
        return drop;
    }

    private List<Block> collectNearbyBlocks(Location center, float power) {
        int radius = Math.max(1, Math.round(power));
        List<Block> result = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if ((x * x + y * y + z * z) > radius * radius) {
                        continue;
                    }
                    Block block = center.getWorld().getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
                    Material type = block.getType();
                    if (type == Material.AIR || type == Material.BEDROCK || type == Material.BARRIER || type == Material.END_PORTAL_FRAME) {
                        continue;
                    }
                    result.add(block);
                }
            }
        }
        return result;
    }

    private boolean isUnderwater(Location location) {
        Material material = location.getBlock().getType();
        return material == Material.WATER || material == Material.KELP || material == Material.KELP_PLANT || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS;
    }

    private boolean isProtectedCoreOrObsidian(Block block) {
        Material type = block.getType();
        return type == Material.IRON_BLOCK
                || type == Material.GOLD_BLOCK
                || type == Material.DIAMOND_BLOCK
                || type == Material.NETHERITE_BLOCK
                || type == Material.OBSIDIAN;
    }

    private boolean isPrivateCore(Material type) {
        return type == Material.IRON_BLOCK
                || type == Material.GOLD_BLOCK
                || type == Material.DIAMOND_BLOCK
                || type == Material.NETHERITE_BLOCK;
    }

    private boolean isOneDamageTier(Tier tier) {
        return tier == Tier.A || tier == Tier.TAERBLACK || tier == Tier.STILER;
    }

    private int getMaxPrivateCoreHealth(Material type) {
        if (type == Material.DIAMOND_BLOCK) {
            return 3;
        }
        if (type == Material.NETHERITE_BLOCK) {
            return 5;
        }
        return 1;
    }

    private boolean applyPrivateCoreDamage(Block block) {
        Material type = block.getType();
        if (!isPrivateCore(type)) {
            return true;
        }

        String key = blockKey(block);
        int max = getMaxPrivateCoreHealth(type);
        int current = privateCoreHealth.getOrDefault(key, max);
        current -= 1;

        if (current <= 0) {
            privateCoreHealth.remove(key);
            saveHealthData();
            return true;
        }

        privateCoreHealth.put(key, current);
        saveHealthData();
        return false;
    }

    private void cleanupCoreData(Block block) {
        String key = blockKey(block);
        privateCoreHealth.remove(key);
        privateCoreOwner.remove(key);
        removeCoreHologram(key);
        saveHealthData();
    }

    private String blockKey(Block block) {
        Location l = block.getLocation();
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private void loadHealthData() {
        privateCoreHealth.clear();
        privateCoreOwner.clear();
        privateCoreHologram.clear();
        if (!healthFile.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(healthFile);
        if (cfg.isConfigurationSection("health")) {
            for (String key : cfg.getConfigurationSection("health").getKeys(false)) {
                int hp = cfg.getInt("health." + key, -1);
                if (hp > 0) {
                    privateCoreHealth.put(key, hp);
                }
            }
        } else {
            // Backward compatibility for old file format.
            List<String> entries = cfg.getStringList("entries");
            for (String entry : entries) {
                int idx = entry.lastIndexOf('=');
                if (idx <= 0 || idx >= entry.length() - 1) {
                    continue;
                }
                String key = entry.substring(0, idx);
                String value = entry.substring(idx + 1);
                try {
                    int hp = Integer.parseInt(value);
                    if (hp > 0) {
                        privateCoreHealth.put(key, hp);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (cfg.isConfigurationSection("owners")) {
            for (String key : cfg.getConfigurationSection("owners").getKeys(false)) {
                String owner = cfg.getString("owners." + key, "");
                if (owner != null && !owner.isEmpty()) {
                    privateCoreOwner.put(key, owner);
                }
            }
        }
    }

    private void saveHealthData() {
        if (healthFile == null) {
            return;
        }
        if (!healthFile.getParentFile().exists()) {
            healthFile.getParentFile().mkdirs();
        }
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Integer> entry : privateCoreHealth.entrySet()) {
            cfg.set("health." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : privateCoreOwner.entrySet()) {
            cfg.set("owners." + entry.getKey(), entry.getValue());
        }
        try {
            cfg.save(healthFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save core-health.yml: " + e.getMessage());
        }
    }

    private void rebuildAllHolograms() {
        List<String> keys = new ArrayList<>(privateCoreOwner.keySet());
        for (String key : keys) {
            Block block = blockFromKey(key);
            if (block == null || !isPrivateCore(block.getType())) {
                privateCoreOwner.remove(key);
                privateCoreHealth.remove(key);
                removeCoreHologram(key);
                continue;
            }
            updateCoreHologram(block);
        }
        saveHealthData();
    }

    private void removeAllHolograms() {
        for (String key : new ArrayList<>(privateCoreHologram.keySet())) {
            removeCoreHologram(key);
        }
    }

    private void removeCoreHologram(String key) {
        UUID uuid = privateCoreHologram.remove(key);
        if (uuid == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(uuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void updateCoreHologram(Block block) {
        if (!isPrivateCore(block.getType())) {
            return;
        }
        String key = blockKey(block);
        String owner = privateCoreOwner.getOrDefault(key, "Unknown");
        int max = getMaxPrivateCoreHealth(block.getType());
        int hp = privateCoreHealth.getOrDefault(key, max);
        hp = Math.max(1, Math.min(max, hp));
        privateCoreHealth.put(key, hp);

        ArmorStand stand = null;
        UUID uuid = privateCoreHologram.get(key);
        if (uuid != null) {
            Entity e = Bukkit.getEntity(uuid);
            if (e instanceof ArmorStand as && e.isValid()) {
                stand = as;
            }
        }

        Location loc = block.getLocation().clone().add(0.5, 1.3, 0.5);
        if (stand == null) {
            stand = block.getWorld().spawn(loc, ArmorStand.class, as -> {
                as.setInvisible(true);
                as.setMarker(true);
                as.setSmall(true);
                as.setGravity(false);
                as.setInvulnerable(true);
                as.setPersistent(false);
                as.setCustomNameVisible(true);
            });
            privateCoreHologram.put(key, stand.getUniqueId());
        } else {
            stand.teleport(loc);
        }

        stand.setCustomName(buildHologramName(block.getType(), owner, hp, max));
    }

    private String buildHologramName(Material type, String owner, int hp, int max) {
        return color("&6" + coreTypeName(type) + " &7| &f" + owner + " &7| &cHP: " + hp + "/" + max);
    }

    private String coreTypeName(Material type) {
        if (type == Material.IRON_BLOCK) return "Железный приват";
        if (type == Material.GOLD_BLOCK) return "Золотой приват";
        if (type == Material.DIAMOND_BLOCK) return "Алмазный приват";
        if (type == Material.NETHERITE_BLOCK) return "Незеритовый приват";
        return "Приват";
    }

    private Block blockFromKey(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return world.getBlockAt(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Tier readTier(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String id = meta.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
        if (id == null || id.isEmpty()) {
            return null;
        }
        return Tier.fromId(id);
    }

    private Tier readTier(Entity entity) {
        String id = entity.getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
        if (id == null || id.isEmpty()) {
            return null;
        }
        return Tier.fromId(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cdyn")) {
            return false;
        }
        if (args.length < 1) {
            sender.sendMessage(color("&eИспользование: /cdyn give <ник> <c|b|a|taerblack|stiler|blastwave> [кол-во]"));
            sender.sendMessage(color("&eИли: /cdyn givejake <ник>"));
            return true;
        }
        if (args[0].equalsIgnoreCase("givejake")) {
            if (!sender.hasPermission("customdynamite.admin")) {
                sender.sendMessage(color("&cНет прав."));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(color("&eИспользование: /cdyn givejake <ник>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(color("&cИгрок не в сети: &f" + args[1]));
                return true;
            }
            target.getInventory().addItem(createJakePickaxeItem());
            sender.sendMessage(color("&aВыдано: &fКирка Джейка &aигроку &f" + target.getName()));
            return true;
        }
        if (!args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(color("&cПоддерживается только: /cdyn give ..."));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(color("&eИспользование: /cdyn give <ник> <c|b|a|taerblack|stiler|blastwave> [кол-во]"));
            return true;
        }
        if (!sender.hasPermission("customdynamite.admin")) {
            sender.sendMessage(color("&cНет прав."));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(color("&cИгрок не в сети: &f" + args[1]));
            return true;
        }

        Tier tier = Tier.fromId(args[2].toLowerCase(Locale.ROOT));
        if (tier == null) {
            sender.sendMessage(color("&cНеизвестный тип динамита."));
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                sender.sendMessage(color("&cКоличество должно быть числом."));
                return true;
            }
        }

        ItemStack item = createDynamiteItem(tier);
        item.setAmount(amount);
        target.getInventory().addItem(item);
        sender.sendMessage(color("&aВыдано: &f" + amount + "x " + tier.id + " &aигроку &f" + target.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("cdyn")) {
            return List.of();
        }
        if (args.length == 1) {
            return startsWith(List.of("give", "givejake"), args[0]);
        }
        if (args.length == 2) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            return startsWith(names, args[1]);
        }
        if (args.length == 3) {
            List<String> tiers = List.of("c", "b", "a", "taerblack", "stiler", "blastwave");
            return startsWith(tiers, args[2]);
        }
        if (args.length == 4) {
            return List.of("1", "8", "16", "32", "64");
        }
        return List.of();
    }

    private List<String> startsWith(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).collect(Collectors.toList());
    }

    private ItemStack createJakePickaxeItem() {
        ItemStack stack = new ItemStack(Material.GOLDEN_PICKAXE, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setDisplayName(color("&6Кирка Джейка"));
        List<String> lore = new ArrayList<>();
        lore.add(color("&7Ломает только спавнеры."));
        lore.add(color("&7Шанс дропа спавнера: &a100%"));
        lore.add(color("&7Прочность: &c1 спавнер"));
        meta.setLore(lore);
        meta.addEnchant(Enchantment.DIG_SPEED, 5, true);
        meta.getPersistentDataContainer().set(jakePickKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean isJakePick(ItemStack stack) {
        if (stack == null || stack.getType() != Material.GOLDEN_PICKAXE) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer().get(jakePickKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private String prettyEntity(EntityType type) {
        String[] parts = type.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private boolean isPlayerInCombat(Player player) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            java.lang.reflect.Method method = papi.getMethod("setPlaceholders", Player.class, String.class);
            Object result = method.invoke(null, player, "%combatlogx_tag_count%");
            if (result == null) {
                return false;
            }
            String value = String.valueOf(result).trim();
            int count = Integer.parseInt(value);
            return count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void consumeOne(Player player, ItemStack item) {
        int amount = item.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        item.setAmount(amount - 1);
        player.getInventory().setItemInMainHand(item);
    }

    private String color(String s) {
        return s.replace('&', '§');
    }
}
