/*
 * Copyright (C) 2014-2015  Erufael
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.gnat008.perworldinventory.data.players;

import me.gnat008.perworldinventory.BukkitService;
import me.gnat008.perworldinventory.PerWorldInventory;
import me.gnat008.perworldinventory.ConsoleLogger;
import me.gnat008.perworldinventory.config.PwiProperties;
import me.gnat008.perworldinventory.config.Settings;
import me.gnat008.perworldinventory.data.DataSource;
import me.gnat008.perworldinventory.data.serializers.DeserializeCause;
import me.gnat008.perworldinventory.events.InventoryLoadCompleteEvent;
import me.gnat008.perworldinventory.groups.Group;
import me.gnat008.perworldinventory.groups.GroupManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static me.gnat008.perworldinventory.util.Utils.checkServerVersion;
import static me.gnat008.perworldinventory.util.Utils.zeroPlayer;

/**
 * This class is used to manage cached players.
 * Players are meant to be added when data needs to be saved, and removed
 * when the data has been saved to the database, whether it be MySQL or
 * flat files.
 */
public class PWIPlayerManager {

    private PerWorldInventory plugin;
    private BukkitService bukkitService;
    private DataSource dataSource;
    private GroupManager groupManager;
    private PWIPlayerFactory pwiPlayerFactory;
    private Settings settings;

    private int interval;
    private BukkitTask task;

    // Key format: uuid.group.gamemode
    private Map<String, PWIPlayer> playerCache = new ConcurrentHashMap<>();

    @Inject
    PWIPlayerManager(PerWorldInventory plugin, BukkitService bukkitService, DataSource dataSource, GroupManager groupManager,
                     PWIPlayerFactory pwiPlayerFactory, Settings settings) {
        this.plugin = plugin;
        this.bukkitService = bukkitService;
        this.dataSource = dataSource;
        this.groupManager = groupManager;
        this.pwiPlayerFactory = pwiPlayerFactory;
        this.settings = settings;

        int setting = settings.getProperty(PwiProperties.SAVE_INTERVAL);
        this.interval = (setting != -1 ? setting : 300) * 20;
    }

    /**
     * Called when the server is disabled.
     */
    public void onDisable() {
        task.cancel();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Group group = groupManager.getGroupFromWorld(player.getWorld().getName());
            savePlayer(group, player, false);
        }

        playerCache.clear();
    }

    /**
     * Add a new player to the cache.
     * <p>
     * Players will be tied to the group they were in. This allows us to have
     * multiple PWIPlayers cached at the same time in case they rapidely change
     * gamemodes or worlds. We can grab data directly from this cache in case
     * they haven't been saved to the database yet.
     *
     * @param player The Player to add
     * @param group The Group the player is in
     *
     * @return The key used to get the player data.
     */
    public String addPlayer(Player player, Group group) {
        String key = makeKey(player.getUniqueId(), group, player.getGameMode());

        ConsoleLogger.debug("Adding player '" + player.getName() + "' to cache; key is '" + key + "'");

        if (playerCache.containsKey(key)) {
            ConsoleLogger.debug("Player '" + player.getName() + "' found in cache! Updating cache");
            updateCache(player, playerCache.get(key));
        } else {
            playerCache.put(key, pwiPlayerFactory.create(player, group));
        }

        return key;
    }

    /**
     * Removes a player from the cache. They key will be made from the player's UUID,
     * the group they are in, and the gamemode of the player.
     *
     * @param player The player to remove from the cache
     */
    public void removePlayer(Player player) {
        for (String key : playerCache.keySet()) {
            if (key.startsWith(player.getUniqueId().toString())) {
                playerCache.remove(key);
            }
        }
    }

    /**
     * Get a player from the cache. This method will
     * return null if no player with the same group and gamemode
     * is cached.
     *
     * @param group The Group the player is in
     * @param player The Player
     * @return The PWIPlayer in the cache, or null
     */
    public PWIPlayer getPlayer(Group group, Player player) {
        String key = makeKey(player.getUniqueId(), group, player.getGameMode());

        return playerCache.get(key);
    }

    /**
     * Get player data from the cache and apply it to
     * the player.
     *
     * @param group The Group the player is in
     * @param gamemode The Gamemode the player is in
     * @param player The Player to get the data for
     * @param cause The the trigger for getting player data
     */
    public void getPlayerData(Group group, GameMode gamemode, Player player, DeserializeCause cause) {
        ConsoleLogger.debug("Trying to get data from cache for player '" + player.getName() + "'");
        zeroPlayer(plugin, player);

        if(isPlayerCached(group, gamemode, player)) {
            getDataFromCache(group, gamemode, player, cause);
        } else {
            ConsoleLogger.debug("Player was not in cache! Loading from file");
            dataSource.getFromDatabase(group, gamemode, player, cause);
        }
    }

    /**
     * Save all cached instances of a player to the disk.
     *
     * @param group The Group the player is currently in.
     * @param player The player to save.
     * @param createTask If a new task should be started.
     */
    public void savePlayer(Group group, Player player, boolean createTask) {
        String key = makeKey(player.getUniqueId(), group, player.getGameMode());

        // Remove any entry with the current key, if one exists
        // Should remove the possibility of having to write the same data twice
        playerCache.remove(key);

        for (String cachedKey : playerCache.keySet()) {
            if (cachedKey.startsWith(player.getUniqueId().toString())) {
                PWIPlayer cached = playerCache.get(cachedKey);
                if (cached.isSaved()) {
                    continue;
                }

                String[] parts = cachedKey.split("\\.");
                Group groupKey = groupManager.getGroup(parts[1]);
                GameMode gamemode = GameMode.valueOf(parts[2].toUpperCase());

                ConsoleLogger.debug("Saving cached player '" + cached.getName() + "' for group '" + groupKey.getName() + "' with gamemdde '" + gamemode.name() + "'");

                cached.setSaved(true);
                if (!createTask) {
                    dataSource.saveToDatabase(groupKey, gamemode, cached);
                } else {
                    bukkitService.runTaskAsync(() -> dataSource.saveToDatabase(groupKey, gamemode, cached));
                }
            }
        }

        PWIPlayer pwiPlayer = pwiPlayerFactory.create(player, group);
        if (!createTask) {
            dataSource.saveToDatabase(group,
                    settings.getProperty(PwiProperties.SEPARATE_GAMEMODE_INVENTORIES) ? player.getGameMode() : GameMode.SURVIVAL,
                    pwiPlayer);
        } else {
            bukkitService.runTaskAsync(() -> dataSource.saveToDatabase(group,
                    settings.getProperty(PwiProperties.SEPARATE_GAMEMODE_INVENTORIES) ? player.getGameMode() : GameMode.SURVIVAL,
                    pwiPlayer));
        }
        dataSource.saveLogoutData(pwiPlayer, createTask); // If we're disabling, cant create a new task
        removePlayer(player);
    }

    /**
     * Return whether a player in a given group is currently cached.
     *
     * @param group The group the player was in.
     * @param gameMode The GameMode the player is in.
     * @param player The player to check for.
     *
     * @return True if a {@link PWIPlayer} is cached.
     */
    public boolean isPlayerCached(Group group, GameMode gameMode, Player player) {
        String key = makeKey(player.getUniqueId(), group, gameMode);

        return playerCache.containsKey(key);
    }

    /**
     * Get a player from the cache, and apply the cached inventories and stats
     * to the actual player. If no matching player is found in the cache, nothing
     * happens and this method simply returns.
     *
     * @param group The {@link Group} the cached player was in.
     * @param gamemode The GameMode the cached player was in.
     * @param player The current actual player to apply the data to.
     * @param cause What triggered the inventory switch; passed on for post-processing.
     */
    private void getDataFromCache(Group group, GameMode gamemode, Player player, DeserializeCause cause) {
        PWIPlayer cachedPlayer = getCachedPlayer(group, gamemode, player.getUniqueId());
        if (cachedPlayer == null) {
            ConsoleLogger.debug("No data for player '" + player.getName() + "' found in cache");

            return;
        }

        ConsoleLogger.debug("Player '" + player.getName() + "' found in cache! Setting their data");

        if (settings.getProperty(PwiProperties.LOAD_ENDER_CHESTS))
            player.getEnderChest().setContents(cachedPlayer.getEnderChest());
        if (settings.getProperty(PwiProperties.LOAD_INVENTORY)) {
            player.getInventory().setContents(cachedPlayer.getInventory());
            player.getInventory().setArmorContents(cachedPlayer.getArmor());
        }
        if (settings.getProperty(PwiProperties.LOAD_CAN_FLY))
            player.setAllowFlight(cachedPlayer.getCanFly());
        if (settings.getProperty(PwiProperties.LOAD_DISPLAY_NAME))
            player.setDisplayName(cachedPlayer.getDisplayName());
        if (settings.getProperty(PwiProperties.LOAD_EXHAUSTION))
            player.setExhaustion(cachedPlayer.getExhaustion());
        if (settings.getProperty(PwiProperties.LOAD_EXP))
            player.setExp(cachedPlayer.getExperience());
        if (settings.getProperty(PwiProperties.LOAD_FLYING) && player.getAllowFlight())
            player.setFlying(cachedPlayer.isFlying());
        if (settings.getProperty(PwiProperties.LOAD_HUNGER))
            player.setFoodLevel(cachedPlayer.getFoodLevel());
        if (settings.getProperty(PwiProperties.LOAD_HEALTH)) {
            if (bukkitService.shouldUseAttributes()) {
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(cachedPlayer.getMaxHealth());
            } else {
                player.setMaxHealth(cachedPlayer.getMaxHealth());
            }
            if (cachedPlayer.getHealth() > 0 && cachedPlayer.getHealth() <= cachedPlayer.getMaxHealth()) {
                player.setHealth(cachedPlayer.getHealth());
            } else {
                player.setHealth(cachedPlayer.getMaxHealth());
            }
        }
        if (settings.getProperty(PwiProperties.LOAD_GAMEMODE) && (!settings.getProperty(PwiProperties.SEPARATE_GAMEMODE_INVENTORIES)))
            player.setGameMode(cachedPlayer.getGamemode());
        if (settings.getProperty(PwiProperties.LOAD_LEVEL))
            player.setLevel(cachedPlayer.getLevel());
        if (settings.getProperty(PwiProperties.LOAD_POTION_EFFECTS)) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            player.addPotionEffects(cachedPlayer.getPotionEffects());
        }
        if (settings.getProperty(PwiProperties.LOAD_SATURATION))
            player.setSaturation(cachedPlayer.getSaturationLevel());
        if (settings.getProperty(PwiProperties.LOAD_FALL_DISTANCE))
            player.setFallDistance(cachedPlayer.getFallDistance());
        if (settings.getProperty(PwiProperties.LOAD_FIRE_TICKS))
            player.setFireTicks(cachedPlayer.getFireTicks());
        if (settings.getProperty(PwiProperties.LOAD_MAX_AIR))
            player.setMaximumAir(cachedPlayer.getMaxAir());
        if (settings.getProperty(PwiProperties.LOAD_REMAINING_AIR))
            player.setRemainingAir(cachedPlayer.getRemainingAir());
        if (settings.getProperty(PwiProperties.USE_ECONOMY)) {
            Economy econ = plugin.getEconomy();
            if (econ == null) {
                ConsoleLogger.warning("Economy saving is turned on, but no economy found!");
                return;
            }

            EconomyResponse er = econ.withdrawPlayer(player, econ.getBalance(player));
            if (er.transactionSuccess()) {
                econ.depositPlayer(player, cachedPlayer.getBalance());
            } else {
                ConsoleLogger.warning("[ECON] Unable to withdraw currency from '" + player.getName() + "': " + er.errorMessage);
            }

            EconomyResponse bankER = econ.bankWithdraw(player.getName(), econ.bankBalance(player.getName()).amount);
            if (bankER.transactionSuccess()) {
                econ.bankDeposit(player.getName(), cachedPlayer.getBankBalance());
            } else {
                ConsoleLogger.warning("[ECON] Unable to withdraw currency from bank of '" + player.getName() + "': " + er.errorMessage);
            }
        }

        InventoryLoadCompleteEvent event = new InventoryLoadCompleteEvent(player, cause);
        Bukkit.getPluginManager().callEvent(event);
    }

    /**
     * Get a PWI player from a UUID.
     * <p>
     * This method will return null if no player is found, or if they have not been
     * saved with the Group given.
     *
     * @param group The Group to grab data from
     * @param gameMode The GameMode to get the data for
     * @param uuid The UUID of the player
     * @return The PWIPlayer
     */
    private PWIPlayer getCachedPlayer(Group group, GameMode gameMode, UUID uuid) {
        String key = makeKey(uuid, group, gameMode);

        ConsoleLogger.debug("Looking for cached data with key '" + key + "'");

        return playerCache.get(key);
    }

    /**
     * Starts a synchronized repeating task to iterate through all PWIPlayers in the player
     * cache. If the player has not yet been saved to a database, they will be saved.
     * <p>
     * Additionally, if a player is still in the cache, but they have already been saved,
     * remove them from the cache.
     * <p>
     * By default, this task will execute once every 5 minutes.
     */
    @PostConstruct
    private void scheduleRepeatingTask() {
        this.task = bukkitService.runRepeatingTask(() -> {
            for (String key : playerCache.keySet()) {
                PWIPlayer player = playerCache.get(key);
                if (!player.isSaved()) {
                    String[] parts = key.split("\\.");
                    Group group = groupManager.getGroup(parts[1]);
                    GameMode gamemode = GameMode.valueOf(parts[2].toUpperCase());

                    ConsoleLogger.debug("Saving cached player with key '" + key + "'");
                    ConsoleLogger.debug("Player: " + player.getName());
                    ConsoleLogger.debug("Group: " + group.getName());
                    ConsoleLogger.debug("Gamemode: " + gamemode.toString());

                    player.setSaved(true);
                    bukkitService.runTaskAsync(() -> dataSource.saveToDatabase(group, gamemode, player));
                } else {
                    ConsoleLogger.debug("Removing player '" + player.getName() + "' from cache");
                    playerCache.remove(key);
                }
            }
        }, interval, interval);
    }

    /**
     * Updates all the values of a player in the cache.
     *
     * @param newData The current snapshot of the Player
     * @param currentPlayer The PWIPlayer currently in the cache
     */
    public void updateCache(Player newData, PWIPlayer currentPlayer) {
        ConsoleLogger.debug("Updating player '" + newData.getName() + "' in the cache");

        currentPlayer.setSaved(false);

        currentPlayer.setArmor(newData.getInventory().getArmorContents());
        currentPlayer.setEnderChest(newData.getEnderChest().getContents());
        currentPlayer.setInventory(newData.getInventory().getContents());

        currentPlayer.setCanFly(newData.getAllowFlight());
        currentPlayer.setDisplayName(newData.getDisplayName());
        currentPlayer.setExhaustion(newData.getExhaustion());
        currentPlayer.setExperience(newData.getExp());
        currentPlayer.setFlying(newData.isFlying());
        currentPlayer.setFoodLevel(newData.getFoodLevel());
        if (checkServerVersion(plugin.getServer().getVersion(), 1, 9, 0)) {
            currentPlayer.setMaxHealth(newData.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());
        } else {
            currentPlayer.setMaxHealth(newData.getMaxHealth());
        }
        currentPlayer.setHealth(newData.getHealth());
        currentPlayer.setLevel(newData.getLevel());
        currentPlayer.setSaturationLevel(newData.getSaturation());
        currentPlayer.setPotionEffects(newData.getActivePotionEffects());
        currentPlayer.setFallDistance(newData.getFallDistance());
        currentPlayer.setFireTicks(newData.getFireTicks());
        currentPlayer.setMaxAir(newData.getMaximumAir());
        currentPlayer.setRemainingAir(newData.getRemainingAir());

        if (plugin.getEconomy() != null) {
            currentPlayer.setBankBalance(plugin.getEconomy().bankBalance(newData.getName()).balance);
            currentPlayer.setBalance(plugin.getEconomy().getBalance(newData));
        }
    }

    /**
     * Create a key to get and save a player's data in the cache.
     * <p>
     *     The format of a key is as follows:
     *     <i>uuid.group-name.gamemode</i>
     * </p>
     *
     * @param uuid The UUID of the player.
     * @param group The Group the player is in.
     * @param gameMode The player's current GameMode.
     * @return The key.
     */
    public String makeKey(UUID uuid, Group group, GameMode gameMode) {
        String key = uuid.toString() + "." + group.getName() + ".";
        if (settings.getProperty(PwiProperties.SEPARATE_GAMEMODE_INVENTORIES))
            key += gameMode.toString().toLowerCase();
        else
            key += "survival";

        return key;
    }
}