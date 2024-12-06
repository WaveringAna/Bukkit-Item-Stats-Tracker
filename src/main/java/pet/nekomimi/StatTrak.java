package pet.nekomimi;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

public class StatTrak extends JavaPlugin implements Listener {
    private static final Pattern STAT_PATTERN = Pattern.compile(".*: (\\d+(?:\\.\\d+)?)$");
    private final Set<UUID> recentlyProcessed = ConcurrentHashMap.newKeySet();
    private static final long CLEANUP_DELAY = 1L;
    
    private enum ItemType {
        WEAPON(material -> material.toString().endsWith("_SWORD") || 
                         material.toString().endsWith("_AXE") || 
                         material == Material.BOW || 
                         material == Material.CROSSBOW),
        TOOL(material -> material.toString().endsWith("_PICKAXE") || 
                        material.toString().endsWith("_AXE") || 
                        material.toString().endsWith("_SHOVEL") || 
                        material.toString().endsWith("_HOE")),
        ARMOR(material -> material.toString().endsWith("_HELMET") ||
                         material.toString().endsWith("_CHESTPLATE") ||
                         material.toString().endsWith("_LEGGINGS") ||
                         material.toString().endsWith("_BOOTS"));

        private final java.util.function.Predicate<Material> matcher;

        ItemType(java.util.function.Predicate<Material> matcher) {
            this.matcher = matcher;
        }

        public boolean matches(Material material) {
            return matcher.test(material);
        }
    }

    private record StatUpdate(String displayName, Number value) {}

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("StatTrak plugin enabled!");
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        
        if (!recentlyProcessed.add(entityId)) {
            return;
        }
        
        getServer().getScheduler().runTaskLater(this, 
            () -> recentlyProcessed.remove(entityId), 
            CLEANUP_DELAY);
        
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (ItemType.WEAPON.matches(weapon.getType())) {
            updateItemStats(weapon, Map.of(
                "StatTrak™ Kills", 
                getStatValue(weapon, "StatTrak™ Kills").doubleValue() + 1
            ));
            killer.getInventory().setItemInMainHand(weapon);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        
        if (ItemType.TOOL.matches(tool.getType())) {
            updateItemStats(tool, Map.of(
                "StatTrak™ Blocks Mined", 
                getStatValue(tool, "StatTrak™ Blocks Mined").doubleValue() + 1
            ));
            player.getInventory().setItemInMainHand(tool);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        
        double damage = event.getFinalDamage();
        ItemStack[] armor = player.getInventory().getArmorContents();
        
        if (armor == null) return;
        
        // Count trackable pieces and prepare updates in one pass
        Map<ItemStack, Double> updates = new HashMap<>();
        int trackablePieces = 0;
        
        for (ItemStack item : armor) {
            if (item != null && ItemType.ARMOR.matches(item.getType())) {
                trackablePieces++;
                updates.put(item, getStatValue(item, "StatTrak™ Damage Taken").doubleValue());
            }
        }
        
        if (trackablePieces > 0) {
            double damagePerPiece = damage / trackablePieces;
            
            // Apply updates in batch
            updates.forEach((item, currentDamage) -> {
                double newTotal = Math.round((currentDamage + damagePerPiece) * 10.0) / 10.0;
                updateItemStats(item, Map.of("StatTrak™ Damage Taken", newTotal));
            });
                
            player.getInventory().setArmorContents(armor);
        }
    }

    private void updateItemStats(ItemStack item, Map<String, Number> updates) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        Map<String, Integer> statIndices = new HashMap<>();
        
        // First pass: find all stat indices
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i);
            for (String statName : updates.keySet()) {
                if (line.contains(statName)) {
                    statIndices.put(statName, i);
                    break;
                }
            }
        }
        
        // Second pass: update or add stats
        updates.forEach((statName, value) -> {
            String formattedValue;
            double doubleValue = value.doubleValue();
            if (doubleValue == Math.floor(doubleValue)) {
                formattedValue = String.format("%d", (int)doubleValue);
            } else {
                formattedValue = String.format("%.1f", doubleValue);
            }
            
            String statDisplay = String.format("§d %s: %s", statName, formattedValue);
            
            Integer index = statIndices.get(statName);
            if (index != null) {
                lore.set(index, statDisplay);
            } else {
                lore.add(statDisplay);
            }
        });
        
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private Number getStatValue(ItemStack item, String statName) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) return 0.0;
        
        return meta.getLore().stream()
            .filter(line -> line.contains(statName))
            .findFirst()
            .map(line -> {
                var matcher = STAT_PATTERN.matcher(line);
                if (matcher.find()) {
                    try {
                        String value = matcher.group(1);
                        return value.contains(".") ? Double.parseDouble(value) : Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        getLogger().warning("Failed to parse number from lore: " + line);
                        return 0.0;
                    }
                }
                return 0.0;
            })
            .orElse(0.0);
    }
}