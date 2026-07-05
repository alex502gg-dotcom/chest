package ru.reallyec;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
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
        if (command.getName().equalsIgnoreCase("reallyec")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("reallyec.reload")) {
                    sender.sendMessage(color(msg("no-permission")));
                    return true;
                }
                reloadConfig();
                sender.sendMessage(color(msg("reload")));
                return true;
            }
            sender.sendMessage(color(msg("unknown-command")));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(color(msg("only-player")));
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("reallyec.use")) {
            player.sendMessage(color(msg("no-permission")));
            return true;
        }

        if (getConfig().getBoolean("settings.open-menu-from-command", true)) {
            openMainMenu(player);
        } else {
            openEnderChest(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("reallyec") && args.length == 1) {
            return Collections.singletonList("reload");
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
        if (!player.hasPermission("reallyec.use")) {
            player.sendMessage(color(msg("no-permission")));
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        openEnderChest(player);
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
                openEnderChest(player);
            } else if (slot == getConfig().getInt("menus.main.items.upgrade.slot", 15)) {
                openUpgradeMenu(player);
            }
            return;
        }

        if (holder instanceof UpgradeMenuHolder) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (getConfig().getBoolean("menus.upgrade.back.enabled", true)
                    && slot == getConfig().getInt("menus.upgrade.back.slot", 49)) {
                openMainMenu(player);
                return;
            }
            Upgrade upgrade = upgradeBySlot(slot);
            if (upgrade != null) {
                buyUpgrade(player, upgrade);
            }
            return;
        }

        if (holder instanceof EnderChestHolder) {
            EnderChestHolder chestHolder = (EnderChestHolder) holder;
            if (!chestHolder.owner.equals(player.getUniqueId())) {
                event.setCancelled(true);
            } else if (event.getRawSlot() >= 0
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
        UUID uuid = ((EnderChestHolder) holder).owner;
        PlayerData data = getData(uuid);
        ItemStack[] contents = event.getInventory().getContents();
        for (int i = 0; i < 54; i++) {
            data.contents[i] = i < contents.length ? contents[i] : null;
        }
        if (getConfig().getBoolean("settings.save-on-close", true)) {
            saveData(uuid, data);
        }
        openCustomChest.remove(uuid);
    }

    private void openMainMenu(Player player) {
        int size = normalizeMenuSize(getConfig().getInt("menus.main.size", 27));
        Inventory inventory = Bukkit.createInventory(new MainMenuHolder(), size, color(getConfig().getString("menus.main.title", "&5Эндер-сундук")));
        fill(inventory, "menus.main.filler");

        PlayerData data = getData(player.getUniqueId());
        Map<String, String> placeholders = placeholders(data.slots);
        setConfiguredItem(inventory, "menus.main.items.open", placeholders);
        setConfiguredItem(inventory, "menus.main.items.upgrade", placeholders);
        player.openInventory(inventory);
    }

    private void openUpgradeMenu(Player player) {
        int size = normalizeMenuSize(getConfig().getInt("menus.upgrade.size", 54));
        Inventory inventory = Bukkit.createInventory(new UpgradeMenuHolder(), size, color(getConfig().getString("menus.upgrade.title", "&eУлучшение эндер-сундука")));
        fill(inventory, "menus.upgrade.filler");

        PlayerData data = getData(player.getUniqueId());
        for (Upgrade upgrade : getUpgrades()) {
            if (upgrade.slot < 0 || upgrade.slot >= size) {
                continue;
            }
            Map<String, String> placeholders = placeholders(upgrade.slots);
            List<String> lore = new ArrayList<String>(upgrade.lore);
            if (data.slots >= upgrade.slots) {
                inventory.setItem(upgrade.slot, itemFromSimpleConfig("menus.upgrade.completed-item", placeholders));
                continue;
            }
            if (data.slots == previousSlots(upgrade.slots)) {
                lore.addAll(formatDynamicLore(getConfig().getStringList("menus.upgrade.locked-lore"), upgrade));
            } else {
                lore.add("");
                lore.add("&cСначала откройте предыдущее улучшение.");
            }
            inventory.setItem(upgrade.slot, makeItem(upgrade.material, upgrade.name, lore, false, placeholders));
        }

        if (getConfig().getBoolean("menus.upgrade.back.enabled", true)) {
            setConfiguredItem(inventory, "menus.upgrade.back", placeholders(data.slots));
        }
        player.openInventory(inventory);
    }

    private void openEnderChest(Player player) {
        PlayerData data = getData(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(
                new EnderChestHolder(player.getUniqueId(), data.slots),
                data.slots,
                color(applyPlaceholders(getConfig().getString("settings.chest-title", "&5Эндер-сундук"), placeholders(data.slots)))
        );
        for (int i = 0; i < data.slots; i++) {
            inventory.setItem(i, data.contents[i]);
        }
        openCustomChest.add(player.getUniqueId());
        player.openInventory(inventory);
    }

    private void buyUpgrade(Player player, Upgrade upgrade) {
        PlayerData data = getData(player.getUniqueId());
        if (data.slots >= getConfig().getInt("settings.max-slots", 54)) {
            player.sendMessage(color(msg("already-max")));
            return;
        }
        if (data.slots != previousSlots(upgrade.slots)) {
            player.sendMessage(color(msg("need-previous")));
            return;
        }
        if (!hasRequirements(player, upgrade.requirements)) {
            player.sendMessage(color(msg("not-enough-items")));
            return;
        }
        takeRequirements(player, upgrade.requirements);
        data.slots = upgrade.slots;
        saveData(player.getUniqueId(), data);
        player.sendMessage(color(msg("upgraded").replace("%slots%", String.valueOf(data.slots))));
        openUpgradeMenu(player);
    }

    private boolean hasRequirements(Player player, Map<Material, Integer> requirements) {
        for (Map.Entry<Material, Integer> entry : requirements.entrySet()) {
            if (countItems(player, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void takeRequirements(Player player, Map<Material, Integer> requirements) {
        for (Map.Entry<Material, Integer> entry : requirements.entrySet()) {
            int left = entry.getValue();
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length && left > 0; i++) {
                ItemStack item = contents[i];
                if (item == null || item.getType() != entry.getKey()) {
                    continue;
                }
                int amount = item.getAmount();
                if (amount <= left) {
                    player.getInventory().setItem(i, null);
                    left -= amount;
                } else {
                    item.setAmount(amount - left);
                    left = 0;
                }
            }
        }
        player.updateInventory();
    }

    private int countItems(Player player, Material material) {
        int amount = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                amount += item.getAmount();
            }
        }
        return amount;
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
            Map<Material, Integer> requirements = new HashMap<Material, Integer>();
            ConfigurationSection req = getConfig().getConfigurationSection(path + ".requirements");
            if (req != null) {
                for (String materialName : req.getKeys(false)) {
                    Material reqMaterial = material(materialName, null);
                    int amount = req.getInt(materialName, 0);
                    if (reqMaterial != null && amount > 0) {
                        requirements.put(reqMaterial, amount);
                    }
                }
            }
            upgrades.add(new Upgrade(slots, getConfig().getInt(path + ".slot", 0), material, name, lore, requirements));
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

    private List<String> formatDynamicLore(List<String> lines, Upgrade upgrade) {
        List<String> result = new ArrayList<String>();
        for (String line : lines) {
            if (line.equals("%requirements%")) {
                if (upgrade.requirements.isEmpty()) {
                    result.add("&aБесплатно");
                } else {
                    for (Map.Entry<Material, Integer> entry : upgrade.requirements.entrySet()) {
                        result.add("&8- &f" + entry.getKey().name() + " &7x" + entry.getValue());
                    }
                }
            } else {
                result.add(line);
            }
        }
        return result;
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

    private Map<String, String> placeholders(int slots) {
        Map<String, String> map = new HashMap<String, String>();
        map.put("%slots%", String.valueOf(slots));
        map.put("%max_slots%", String.valueOf(getConfig().getInt("settings.max-slots", 54)));
        return map;
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        String result = text == null ? "" : text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
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
        private final Map<Material, Integer> requirements;

        private Upgrade(int slots, int slot, Material material, String name, List<String> lore, Map<Material, Integer> requirements) {
            this.slots = slots;
            this.slot = slot;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.requirements = requirements;
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

    private static final class EnderChestHolder implements InventoryHolder {
        private final UUID owner;
        private final int unlockedSlots;

        private EnderChestHolder(UUID owner, int unlockedSlots) {
            this.owner = owner;
            this.unlockedSlots = unlockedSlots;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
