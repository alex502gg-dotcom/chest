package ru.reallyec;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ReallyECPlugin extends JavaPlugin implements Listener, TabExecutor {
    private File playersFolder;
    private final Map<UUID, PlayerData> cache = new HashMap<UUID, PlayerData>();
    private final Set<UUID> openCustomChest = new HashSet<UUID>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        playersFolder = new File(getDataFolder(), "players");
        if (!playersFolder.exists() && !playersFolder.mkdirs()) {
            getLogger().warning("Не удалось создать папку players.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("ec").setExecutor(this);
        getCommand("ec").setTabCompleter(this);
        getCommand("reallyec").setExecutor(this);
        getCommand("reallyec").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, PlayerData> entry : cache.entrySet()) {
            saveData(entry.getKey(), entry.getValue());
        }
        cache.clear();
        openCustomChest.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase();
            if (sub.equals("reload")) {
                return reloadCommand(sender);
            }
            if (sub.equals("clear")) {
                return clearCommand(sender, args);
            }
            if (sub.equals("invsee")) {
                return invseeCommand(sender, args);
            }
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(color(msg("only-player")));
            return true;
        }

        Player player = (Player) sender;
        if (!hasPerm(player, "ecrw.use")) {
            player.sendMessage(color(msg("no-permission")));
            return true;
        }

        if (getConfig().getBoolean("settings.open-menu-from-command", true)) {
            openMainMenu(player);
        } else {
            openEnderChest(player, player.getUniqueId(), player.getName(), true, false);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(args[0], Arrays.asList("reload", "clear", "invsee"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            return filter(args[1], Arrays.asList("upgrade", "inv"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("clear")) {
            List<String> values = new ArrayList<String>();
            values.add("*");
            for (Player player : Bukkit.getOnlinePlayers()) {
                values.add(player.getName());
            }
            return filter(args[2], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("invsee")) {
            List<String> values = new ArrayList<String>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                values.add(player.getName());
            }
            return filter(args[1], values);
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnderChestInteract(PlayerInteractEvent event) {
        if (!getConfig().getBoolean("settings.open-custom-chest-from-block", true)) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block.getType() != Material.ENDER_CHEST) {
            return;
        }
        Player player = event.getPlayer();
        if (!hasPerm(player, "ecrw.use")) {
            player.sendMessage(color(msg("no-permission")));
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        openEnderChest(player, player.getUniqueId(), player.getName(), true, false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof MainMenuHolder) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == getConfig().getInt("menus.main.items.open.slot", 11)) {
                openEnderChest(player, player.getUniqueId(), player.getName(), true, false);
            } else if (slot == getConfig().getInt("menus.main.items.upgrade.slot", 15)) {
                openUpgradeMenu(player);
            }
            return;
        }

        if (holder instanceof UpgradeMenuHolder) {
            event.setCancelled(true);
            Upgrade upgrade = upgradeBySlot(event.getRawSlot());
            if (upgrade != null) {
                PlayerData data = getData(player.getUniqueId());
                if (data.slots == previousSlots(upgrade.slots)) {
                    openConfirmMenu(player, upgrade);
                } else if (data.slots >= upgrade.slots) {
                    player.sendMessage(color(msg("already-bought")));
                } else {
                    player.sendMessage(color(msg("need-previous")));
                }
            }
            return;
        }

        if (holder instanceof ConfirmMenuHolder) {
            event.setCancelled(true);
            ConfirmMenuHolder confirm = (ConfirmMenuHolder) holder;
            int slot = event.getRawSlot();
            if (slot == getConfig().getInt("menus.confirm.yes.slot", 11)) {
                buyUpgrade(player, confirm.upgrade);
            } else if (slot == getConfig().getInt("menus.confirm.no.slot", 15)) {
                openUpgradeMenu(player);
            }
            return;
        }

        if (holder instanceof EnderChestHolder) {
            EnderChestHolder chestHolder = (EnderChestHolder) holder;
            if (!chestHolder.editable) {
                event.setCancelled(true);
                return;
            }
            if (event.getRawSlot() >= 0
                    && event.getRawSlot() < event.getInventory().getSize()
                    && event.getRawSlot() >= chestHolder.unlockedSlots) {
                event.setCancelled(true);
                player.sendMessage(color(msg("blocked-slot")));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof EnderChestHolder)) {
            return;
        }
        EnderChestHolder chestHolder = (EnderChestHolder) holder;
        if (!chestHolder.editable) {
            event.setCancelled(true);
            return;
        }
        for (Integer rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < event.getInventory().getSize() && rawSlot >= chestHolder.unlockedSlots) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player) {
                    ((Player) event.getWhoClicked()).sendMessage(color(msg("blocked-slot")));
                }
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof EnderChestHolder)) {
            return;
        }
        EnderChestHolder chestHolder = (EnderChestHolder) holder;
        if (!chestHolder.editable) {
            return;
        }
        PlayerData data = getData(chestHolder.owner);
        ItemStack[] contents = event.getInventory().getContents();
        for (int i = 0; i < 54; i++) {
            data.contents[i] = i < contents.length ? contents[i] : null;
        }
        if (getConfig().getBoolean("settings.save-on-close", true)) {
            saveData(chestHolder.owner, data);
        }
        openCustomChest.remove(chestHolder.owner);
    }

    private boolean reloadCommand(CommandSender sender) {
        if (!hasPerm(sender, "ecrw.reload")) {
            sender.sendMessage(color(msg("no-permission")));
            return true;
        }
        reloadConfig();
        sender.sendMessage(color(msg("reload")));
        return true;
    }

    private boolean clearCommand(CommandSender sender, String[] args) {
        if (!hasPerm(sender, "ecrw.clear")) {
            sender.sendMessage(color(msg("no-permission")));
            return true;
        }
        if (args.length != 3 || (!args[1].equalsIgnoreCase("upgrade") && !args[1].equalsIgnoreCase("inv"))) {
            sender.sendMessage(color(msg("clear-usage")));
            return true;
        }

        boolean clearUpgrade = args[1].equalsIgnoreCase("upgrade");
        if (args[2].equals("*")) {
            int count = clearAll(clearUpgrade);
            sender.sendMessage(color(msg(clearUpgrade ? "clear-upgrade-all" : "clear-inv-all")
                    .replace("%count%", String.valueOf(count))));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            sender.sendMessage(color(msg("player-not-found")));
            return true;
        }
        clearPlayer(target.getUniqueId(), clearUpgrade);
        sender.sendMessage(color(msg(clearUpgrade ? "clear-upgrade-player" : "clear-inv-player")
                .replace("%player%", target.getName() == null ? args[2] : target.getName())));
        return true;
    }

    private boolean invseeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color(msg("only-player")));
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(color(msg("invsee-usage")));
            return true;
        }
        Player viewer = (Player) sender;
        boolean admin = hasPerm(viewer, "ecrw.invsee.admin");
        boolean viewOnly = hasPerm(viewer, "ecrw.invsee.player");
        if (!admin && !viewOnly) {
            viewer.sendMessage(color(msg("no-permission")));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
            viewer.sendMessage(color(msg("player-not-found")));
            return true;
        }
        openEnderChest(viewer, target.getUniqueId(), target.getName() == null ? args[1] : target.getName(), admin, true);
        return true;
    }

    private void openMainMenu(Player player) {
        int size = normalizeMenuSize(getConfig().getInt("menus.main.size", 27));
        Inventory inventory = Bukkit.createInventory(new MainMenuHolder(), size, color(getConfig().getString("menus.main.title", "&5Эндер-сундук")));
        fill(inventory, "menus.main.filler");

        PlayerData data = getData(player.getUniqueId());
        Map<String, String> placeholders = placeholders(data.slots, 0);
        setConfiguredItem(inventory, "menus.main.items.open", placeholders);
        setConfiguredItem(inventory, "menus.main.items.upgrade", placeholders);
        player.openInventory(inventory);
    }

    private void openUpgradeMenu(Player player) {
        int size = normalizeMenuSize(getConfig().getInt("menus.upgrade.size", 54));
        Inventory inventory = Bukkit.createInventory(new UpgradeMenuHolder(), size, color(getConfig().getString("menus.upgrade.title", "&eУлучшение эндер-сундука")));
        PlayerData data = getData(player.getUniqueId());

        ItemStack unlocked = itemFromSimpleConfig("menus.upgrade.unlocked-slot", placeholders(data.slots, 0));
        ItemStack locked = itemFromSimpleConfig("menus.upgrade.locked-slot", placeholders(data.slots, 0));
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, i < data.slots ? unlocked : locked);
        }

        for (Upgrade upgrade : getUpgrades()) {
            if (upgrade.slot < 0 || upgrade.slot >= size) {
                continue;
            }
            Map<String, String> placeholders = placeholders(upgrade.slots, upgrade.price);
            List<String> lore = new ArrayList<String>(upgrade.lore);
            if (data.slots >= upgrade.slots) {
                lore.addAll(getConfig().getStringList("menus.upgrade.bought-lore"));
            } else if (data.slots == previousSlots(upgrade.slots)) {
                lore.addAll(getConfig().getStringList("menus.upgrade.available-lore"));
            } else {
                lore.addAll(getConfig().getStringList("menus.upgrade.need-previous-lore"));
            }
            inventory.setItem(upgrade.slot, makeItem(upgrade.material, upgrade.name, lore, false, placeholders));
        }
        player.openInventory(inventory);
    }

    private void openConfirmMenu(Player player, Upgrade upgrade) {
        int size = normalizeMenuSize(getConfig().getInt("menus.confirm.size", 27));
        Inventory inventory = Bukkit.createInventory(new ConfirmMenuHolder(upgrade), size, color(getConfig().getString("menus.confirm.title", "&6Подтверждение")));
        fill(inventory, "menus.confirm.filler");
        Map<String, String> placeholders = placeholders(upgrade.slots, upgrade.price);
        setConfiguredItem(inventory, "menus.confirm.info", placeholders);
        setConfiguredItem(inventory, "menus.confirm.yes", placeholders);
        setConfiguredItem(inventory, "menus.confirm.no", placeholders);
        player.openInventory(inventory);
    }

    private void openEnderChest(Player viewer, UUID owner, String ownerName, boolean editable, boolean invsee) {
        PlayerData data = getData(owner);
        Map<String, String> placeholders = placeholders(data.slots, 0);
        placeholders.put("%player%", ownerName == null ? owner.toString() : ownerName);
        String titlePath = invsee ? "settings.invsee-title" : "settings.chest-title";
        Inventory inventory = Bukkit.createInventory(
                new EnderChestHolder(owner, data.slots, editable),
                data.slots,
                color(applyPlaceholders(getConfig().getString(titlePath, "&5Эндер-сундук"), placeholders))
        );
        for (int i = 0; i < data.slots; i++) {
            inventory.setItem(i, data.contents[i]);
        }
        openCustomChest.add(owner);
        viewer.openInventory(inventory);
    }

    private void buyUpgrade(Player player, Upgrade upgrade) {
        PlayerData data = getData(player.getUniqueId());
        if (data.slots >= getConfig().getInt("settings.max-slots", 54)) {
            player.sendMessage(color(msg("already-max")));
            openUpgradeMenu(player);
            return;
        }
        if (data.slots != previousSlots(upgrade.slots)) {
            player.sendMessage(color(msg("need-previous")));
            openUpgradeMenu(player);
            return;
        }
        if (!EssentialsMoney.has(player.getUniqueId(), upgrade.price)) {
            player.sendMessage(color(msg("not-enough-money").replace("%price%", formatMoney(upgrade.price))));
            openUpgradeMenu(player);
            return;
        }
        if (!EssentialsMoney.take(player.getUniqueId(), upgrade.price)) {
            player.sendMessage(color(msg("economy-error")));
            openUpgradeMenu(player);
            return;
        }
        data.slots = upgrade.slots;
        saveData(player.getUniqueId(), data);
        player.sendMessage(color(msg("upgraded")
                .replace("%slots%", String.valueOf(data.slots))
                .replace("%price%", formatMoney(upgrade.price))));
        openUpgradeMenu(player);
    }

    private int clearAll(boolean clearUpgrade) {
        Set<UUID> uuids = new HashSet<UUID>(cache.keySet());
        if (playersFolder.exists()) {
            File[] files = playersFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.endsWith(".yml")) {
                        try {
                            uuids.add(UUID.fromString(name.substring(0, name.length() - 4)));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        }
        for (UUID uuid : uuids) {
            clearPlayer(uuid, clearUpgrade);
        }
        return uuids.size();
    }

    private void clearPlayer(UUID uuid, boolean clearUpgrade) {
        PlayerData data = getData(uuid);
        Arrays.fill(data.contents, null);
        if (clearUpgrade) {
            data.slots = clampSlots(getConfig().getInt("settings.default-slots", 27));
        }
        saveData(uuid, data);
    }

    private PlayerData getData(UUID uuid) {
        PlayerData cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        File file = new File(playersFolder, uuid.toString() + ".yml");
        PlayerData data = new PlayerData();
        data.slots = clampSlots(getConfig().getInt("settings.default-slots", 27));
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            data.slots = clampSlots(yaml.getInt("slots", data.slots));
            List<?> list = yaml.getList("contents", Collections.emptyList());
            for (int i = 0; i < Math.min(54, list.size()); i++) {
                Object value = list.get(i);
                if (value instanceof ItemStack) {
                    data.contents[i] = (ItemStack) value;
                }
            }
        }
        cache.put(uuid, data);
        return data;
    }

    private void saveData(UUID uuid, PlayerData data) {
        if (!playersFolder.exists() && !playersFolder.mkdirs()) {
            getLogger().warning("Не удалось создать папку players.");
            return;
        }
        File file = new File(playersFolder, uuid.toString() + ".yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("slots", clampSlots(data.slots));
        yaml.set("contents", Arrays.asList(data.contents));
        try {
            yaml.save(file);
        } catch (IOException exception) {
            getLogger().warning("Не удалось сохранить эндер-сундук игрока " + uuid + ": " + exception.getMessage());
        }
    }

    private List<Upgrade> getUpgrades() {
        List<Upgrade> upgrades = new ArrayList<Upgrade>();
        ConfigurationSection section = getConfig().getConfigurationSection("upgrades");
        if (section == null) {
            return upgrades;
        }
        for (String key : section.getKeys(false)) {
            int slots;
            try {
                slots = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            slots = clampSlots(slots);
            String path = "upgrades." + key;
            Material material = material(getConfig().getString(path + ".material"), Material.ENDER_CHEST);
            String name = getConfig().getString(path + ".name", "&dУлучшение до " + slots + " слотов");
            List<String> lore = getConfig().getStringList(path + ".lore");
            double price = getConfig().getDouble(path + ".price", 0.0);
            upgrades.add(new Upgrade(slots, getConfig().getInt(path + ".slot", 0), material, name, lore, price));
        }
        Collections.sort(upgrades, new Comparator<Upgrade>() {
            @Override
            public int compare(Upgrade first, Upgrade second) {
                return Integer.compare(first.slots, second.slots);
            }
        });
        return upgrades;
    }

    private Upgrade upgradeBySlot(int slot) {
        for (Upgrade upgrade : getUpgrades()) {
            if (upgrade.slot == slot) {
                return upgrade;
            }
        }
        return null;
    }

    private int previousSlots(int targetSlots) {
        int previous = clampSlots(getConfig().getInt("settings.default-slots", 27));
        for (Upgrade upgrade : getUpgrades()) {
            if (upgrade.slots >= targetSlots) {
                break;
            }
            previous = upgrade.slots;
        }
        return previous;
    }

    private void fill(Inventory inventory, String path) {
        if (!getConfig().getBoolean(path + ".enabled", true)) {
            return;
        }
        ItemStack item = itemFromSimpleConfig(path, Collections.<String, String>emptyMap());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, item);
        }
    }

    private void setConfiguredItem(Inventory inventory, String path, Map<String, String> placeholders) {
        int slot = getConfig().getInt(path + ".slot", -1);
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, itemFromSimpleConfig(path, placeholders));
        }
    }

    private ItemStack itemFromSimpleConfig(String path, Map<String, String> placeholders) {
        Material mat = material(getConfig().getString(path + ".material"), Material.STONE);
        String name = getConfig().getString(path + ".name", "");
        List<String> lore = getConfig().getStringList(path + ".lore");
        boolean glow = getConfig().getBoolean(path + ".glow", false);
        ItemStack item = makeItem(mat, name, lore, glow, placeholders);
        int customModelData = getConfig().getInt(path + ".custom-model-data", 0);
        if (customModelData > 0 && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            meta.setCustomModelData(customModelData);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeItem(Material material, String name, List<String> lore, boolean glow, Map<String, String> placeholders) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(applyPlaceholders(name, placeholders)));
            List<String> coloredLore = new ArrayList<String>();
            for (String line : lore) {
                coloredLore.add(color(applyPlaceholders(line, placeholders)));
            }
            meta.setLore(coloredLore);
            if (glow) {
                meta.addEnchant(Enchantment.DURABILITY, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Map<String, String> placeholders(int slots, double price) {
        Map<String, String> map = new HashMap<String, String>();
        map.put("%slots%", String.valueOf(slots));
        map.put("%max_slots%", String.valueOf(getConfig().getInt("settings.max-slots", 54)));
        map.put("%price%", formatMoney(price));
        return map;
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        String result = text == null ? "" : text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private boolean hasPerm(CommandSender sender, String permission) {
        return sender.hasPermission("ecrw.*") || sender.hasPermission(permission);
    }

    private String formatMoney(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private List<String> filter(String start, List<String> values) {
        List<String> result = new ArrayList<String>();
        String lower = start.toLowerCase();
        for (String value : values) {
            if (value.toLowerCase().startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private Material material(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        return material == null ? fallback : material;
    }

    private int normalizeMenuSize(int size) {
        if (size < 9) {
            return 9;
        }
        if (size > 54) {
            return 54;
        }
        return ((size + 8) / 9) * 9;
    }

    private int clampSlots(int slots) {
        int min = getConfig().getInt("settings.default-slots", 27);
        int max = getConfig().getInt("settings.max-slots", 54);
        slots = Math.max(min, Math.min(max, slots));
        return normalizeMenuSize(slots);
    }

    private static final class EssentialsMoney {
        private static boolean has(UUID uuid, double amount) {
            if (amount <= 0) {
                return true;
            }
            try {
                Class<?> economy = Class.forName("com.earth2me.essentials.api.Economy");
                Method getMoney = economy.getMethod("getMoneyExact", UUID.class);
                BigDecimal balance = (BigDecimal) getMoney.invoke(null, uuid);
                return balance.compareTo(BigDecimal.valueOf(amount)) >= 0;
            } catch (Exception ignored) {
                return false;
            }
        }

        private static boolean take(UUID uuid, double amount) {
            if (amount <= 0) {
                return true;
            }
            try {
                Class<?> economy = Class.forName("com.earth2me.essentials.api.Economy");
                Method subtract = economy.getMethod("subtract", UUID.class, BigDecimal.class);
                subtract.invoke(null, uuid, BigDecimal.valueOf(amount));
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static final class PlayerData {
        private int slots;
        private final ItemStack[] contents = new ItemStack[54];
    }

    private static final class Upgrade {
        private final int slots;
        private final int slot;
        private final Material material;
        private final String name;
        private final List<String> lore;
        private final double price;

        private Upgrade(int slots, int slot, Material material, String name, List<String> lore, double price) {
            this.slots = slots;
            this.slot = slot;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.price = price;
        }
    }

    private static final class MainMenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class UpgradeMenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class ConfirmMenuHolder implements InventoryHolder {
        private final Upgrade upgrade;

        private ConfirmMenuHolder(Upgrade upgrade) {
            this.upgrade = upgrade;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final class EnderChestHolder implements InventoryHolder {
        private final UUID owner;
        private final int unlockedSlots;
        private final boolean editable;

        private EnderChestHolder(UUID owner, int unlockedSlots, boolean editable) {
            this.owner = owner;
            this.unlockedSlots = unlockedSlots;
            this.editable = editable;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
