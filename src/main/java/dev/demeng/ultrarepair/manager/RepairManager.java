package dev.demeng.ultrarepair.manager;

import com.sun.org.slf4j.internal.Logger;
import dev.demeng.pluginbase.Common;
import dev.demeng.pluginbase.Schedulers;
import dev.demeng.pluginbase.Services;
import dev.demeng.pluginbase.Sounds;
import dev.demeng.pluginbase.lib.xseries.XSound;
import dev.demeng.pluginbase.serialize.ItemSerializer;
import dev.demeng.pluginbase.text.Text;
import dev.demeng.ultrarepair.UltraRepair;
import io.github.bananapuncher714.nbteditor.NBTEditor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class RepairManager {

  private static final String COOLDOWN_BYPASS_PERMISSION = "ultrarepair.bypass.cooldown";
  private static final String COST_BYPASS_PERMISSION = "ultrarepair.bypass.cost";

  private static final String EXCLUDE_NBT_TAG = "ultrarepair:exclude";

  private final UltraRepair i;
  private final Map<Player, Long> cooldowns = new HashMap<>();
  private final Map<ItemStack, Double> costExceptions = new HashMap<>();
  private final Map<Material, Set<Integer>> excludedModelData = new HashMap<>();

  private long handCooldown;
  private long allCooldown;
  private double defaultCost;
  private double durabilityCostMultiplier;
  private Sound handSound;
  private Sound allSound;

  public RepairManager(UltraRepair i) {
    this.i = i;
    reload();
  }

  public void reload() {

    costExceptions.clear();
    excludedModelData.clear();

    this.handCooldown = i.getSettings().getLong("cooldown.hand") * 1000L;
    this.allCooldown = i.getSettings().getLong("cooldown.all") * 1000L;
    this.defaultCost = i.getSettings().getDouble("default-cost");
    this.durabilityCostMultiplier = i.getSettings().getDouble("durability-multiplier");

    final ConfigurationSection section = Objects.requireNonNull(
        i.getSettings().getConfigurationSection("cost-exceptions"),
        "Cost exceptions section is null");

    for (String key : section.getKeys(false)) {
      costExceptions.put(ItemSerializer.deserialize(
              Objects.requireNonNull(section.getConfigurationSection(key))),
          section.getDouble(key + ".cost"));
    }

    // Load exclusion by CustomModelData
    final ConfigurationSection excludeSec = i.getSettings().getConfigurationSection("exclude");
    if (excludeSec != null) {
      for (String matName : excludeSec.getKeys(false)) {
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
          continue;
        }
        final List<Integer> list = excludeSec.getIntegerList(matName + ".customdata");
        if (list != null && !list.isEmpty()) {
          excludedModelData.put(mat, new HashSet<>(list));
        }
      }
    }

    this.handSound = XSound.matchXSound(Objects.requireNonNull(
        i.getSettings().getString("sound.hand"))).orElse(XSound.BLOCK_ANVIL_USE).parseSound();
    this.allSound = XSound.matchXSound(Objects.requireNonNull(
        i.getSettings().getString("sound.all"))).orElse(XSound.BLOCK_ANVIL_USE).parseSound();
  }

  /**
   * Checks if an item can potentially be repaired if it were damaged and not excluded from repair.
   *
   * @param stack The item to check
   * @return true if the item can potentially be repaired, false otherwise
   */
  public boolean isPotentiallyRepairable(ItemStack stack) {
    return stack != null
        && stack.getType() != Material.AIR
        && (!Common.isServerVersionAtLeast(13) || !stack.getType().isAir())
        && !stack.getType().isBlock()
        && !stack.getType().isEdible()
        && stack.getType().getMaxDurability() > 0;
  }

  /**
   * Checks if an item is ready to be repaired (i.e., is valid, currently damaged, and does not have
   * an exclusion tag).
   *
   * @param stack The item to check
   * @return true if the item is valid and damaged, false otherwise
   * @see #isPotentiallyRepairable(ItemStack)
   */
  public boolean isRepairable(ItemStack stack) {
    // stack#getDurability() is 0 when the item is not damaged.
    return isPotentiallyRepairable(stack)
        && !hasExclusionTag(stack)
        && stack.getDurability() != 0;
  }

  public boolean hasExclusionTag(ItemStack stack) {
    if (stack == null || stack.getType() == Material.AIR) {
      return false;
    }

    // Check legacy NBT exclusion tag first
    if (NBTEditor.contains(stack, EXCLUDE_NBT_TAG)) {
      return true;
    }

    // Check exclusion by CustomModelData from config
    final Set<Integer> excluded = excludedModelData.get(stack.getType());
    if (excluded == null || excluded.isEmpty()) {
      return false;
    }

    final ItemMeta meta = stack.getItemMeta();
    if (meta == null) {
      return false;
    }

    try {
      if (meta.hasCustomModelData()) {
        final int cmd = meta.getCustomModelData();
        return excluded.contains(cmd);
      }
    } catch (NoSuchMethodError ignored) {
      // Running on an API version without CustomModelData support
      return false;
    }

    return false;
  }

  public ItemStack addExclusionTag(ItemStack stack) {

    if (stack == null
        || stack.getType() == Material.AIR
        || NBTEditor.contains(stack, EXCLUDE_NBT_TAG)) {
      return stack;
    }

    return NBTEditor.set(stack, true, EXCLUDE_NBT_TAG);
  }

  public ItemStack removeExclusionTag(ItemStack stack) {

    if (stack == null
        || stack.getType() == Material.AIR
        || !NBTEditor.contains(stack, EXCLUDE_NBT_TAG)) {
      return stack;
    }

    return NBTEditor.set(stack, NBTEditor.DELETE, EXCLUDE_NBT_TAG);
  }

  public boolean hasAnyRepairable(Player p) {

    for (ItemStack stack : getInventoryContents(p)) {
      if (isRepairable(stack)) {
        return true;
      }
    }

    return false;
  }

  public long getHandCooldown(Player p) {

    if (isBypassingCooldown(p)) {
      return 0;
    }

    return handCooldown;
  }

  public long getAllCooldown(Player p) {

    if (isBypassingCooldown(p)) {
      return 0;
    }

    return allCooldown;
  }

  public long getRemainingCooldownMs(Player p) {

    if (isBypassingCooldown(p)) {
      cooldowns.remove(p);
      return 0L;
    }

    if (!cooldowns.containsKey(p)) {
      return 0L;
    }

    final long remaining = cooldowns.get(p) - System.currentTimeMillis();

    if (remaining <= 0) {
      cooldowns.remove(p);
      return 0L;
    }

    return remaining;
  }

  public double calculateItemCost(Player p, ItemStack stack) {

    if (isBypassingCost(p)) {
      return 0;
    }

    if (!isRepairable(stack)) {
      return 0;
    }

    final ItemStack copy = new ItemStack(stack);
    copy.setDurability((short) 0);

    double cost = defaultCost;

    for (Map.Entry<ItemStack, Double> entry : costExceptions.entrySet()) {
      if (isBasedOn(copy, entry.getKey())) {
        cost = entry.getValue();
        break;
      }
    }

    return (cost + (stack.getDurability() * durabilityCostMultiplier)) * stack.getAmount();
  }

    /**
     * Checks if an item (item1) is based on another item (item2)
     * So if item1 has displayName but the 2nd doesnt - it is based on item2
     * But if item2 has lore and item1 doesnt - it is not based on item2
     * @param item1
     * @param item2
     * @return
     */
    private boolean isBasedOn(ItemStack item1, ItemStack item2) {
        if (item1 == null || item2 == null) return false;
        if (!item1.getType().equals(item2.getType())) return false;

        ItemStack copy1 = item1.clone();
        ItemStack copy2 = item2.clone();
        copy1.setAmount(1);
        copy2.setAmount(1);
        ItemMeta meta1 = copy1.getItemMeta();
        ItemMeta meta2 = copy2.getItemMeta();

        if (meta2 == null) return meta1 == null;

        // Display Name
        String name2 = meta2.hasDisplayName() ? meta2.getDisplayName() : null;
        String name1 = meta1.hasDisplayName() ? meta1.getDisplayName() : null;
        if (name2 != null && !Objects.equals(name2, name1)) return false;

        // Lore
        List<String> lore2 = meta2.hasLore() ? meta2.getLore() : null;
        List<String> lore1 = meta1.hasLore() ? meta1.getLore() : null;
        if (lore2 != null && !Objects.equals(lore2, lore1)) return false;

        // Enchants
        Map<Enchantment, Integer> ench2 = meta2.getEnchants();
        Map<Enchantment, Integer> ench1 = meta1.getEnchants();
        if (!ench2.isEmpty() && !Objects.equals(ench2, ench1)) return false;

        // Custom Model Data
        if (meta2.hasCustomModelData()) {
            if (!meta1.hasCustomModelData() || meta1.getCustomModelData() != meta2.getCustomModelData())
                return false;
        }

        return true;
    }



    public double calculateInventoryCost(Player p) {

    double cost = 0;

    for (ItemStack stack : getInventoryContents(p)) {
      cost += calculateItemCost(p, stack);
    }

    return cost;
  }

  // Does not check for preconditions.
  public void repairHand(Player p) {

    cooldowns.put(p, System.currentTimeMillis() + handCooldown);
    Schedulers.sync().runLater(() -> cooldowns.remove(p), handCooldown / 50L);

    final ItemStack stack = p.getItemInHand();

    if (i.isEconomyEnabled()) {
      final Economy eco = Services.get(Economy.class).orElseThrow(NullPointerException::new);
      eco.withdrawPlayer(p, calculateItemCost(p, stack));
    }

    stack.setDurability((short) 0);
    Sounds.playVanillaToPlayer(p, handSound, 1F, 1F);
  }

  // Does not check for preconditions.
  public boolean repairAll(Player p) {

    cooldowns.put(p, System.currentTimeMillis() + allCooldown);
    Schedulers.sync().runLater(() -> cooldowns.remove(p), allCooldown / 50L);

    if (i.isEconomyEnabled()) {
      final Economy eco = Services.get(Economy.class).orElseThrow(NullPointerException::new);
      eco.withdrawPlayer(p, calculateInventoryCost(p));
    }

    boolean hadExcludedDamaged = false;

    for (ItemStack stack : getInventoryContents(p)) {
      if (isRepairable(stack)) {
        stack.setDurability((short) 0);
      } else if (stack != null && isPotentiallyRepairable(stack) && stack.getDurability() != 0 && hasExclusionTag(stack)) {
        hadExcludedDamaged = true;
      }
    }

    Sounds.playVanillaToPlayer(p, allSound, 1F, 1F);
    return hadExcludedDamaged;
  }

  public static boolean isBypassingCost(Player p) {
    return p.hasPermission(COST_BYPASS_PERMISSION);
  }

  public static boolean isBypassingCooldown(Player p) {
    return p.hasPermission(COOLDOWN_BYPASS_PERMISSION);
  }

  public static List<ItemStack> getInventoryContents(Player p) {
    final List<ItemStack> contents = new ArrayList<>();

    for (ItemStack stack : p.getInventory().getContents()) {
      if (stack != null && stack.getType() != Material.AIR) {
        contents.add(stack);
      }
    }

    // MC 1.8's getContents() does not contain armor slots
    if (Common.getServerMajorVersion() == 8) {
      for (ItemStack stack : p.getInventory().getArmorContents()) {
        if (stack != null && stack.getType() != Material.AIR) {
          contents.add(stack);
        }
      }
    }

    return contents;
  }
}
