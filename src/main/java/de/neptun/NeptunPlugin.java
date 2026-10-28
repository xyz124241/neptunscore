package de.neptun;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import de.neptun.core.Module;
import de.neptun.core.NeptunCore;
import org.bukkit.Particle;
import org.bukkit.Sound;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class NeptunPlugin extends Module implements Listener {
    public NeptunPlugin(NeptunCore host) { super(host, "neptun"); }
    @Override public String[] commands() { return new String[]{"spawn", "setspawn", "home", "sethome", "delhome", "rtp"}; }
    @Override public String legacyFolder() { return "Neptun"; }
    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    private final Map<UUID, Long> rtpCooldowns = new HashMap<>();
    private final Map<UUID, Long> pendingRtp = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadHomes();
        Bukkit.getPluginManager().registerEvents(this, host());
        getLogger().info("[neptun] Spawn/Homes/RTP aktiv.");
    }

    @Override public void onDisable() { saveHomes(); }

    private String msg(String s) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("prefix", "&8[&f&lNeptun&8] &f") + s);
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void tell(CommandSender sender, String s) { sender.sendMessage(msg(s)); }
    private boolean isAdmin(Player p) { return p.hasPermission("neptun.admin"); }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { tell(sender, "&cNur im Spiel verfügbar."); return true; }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                Location loc = getConfig().getLocation("spawns." + p.getWorld().getName());
                if (loc == null) loc = p.getWorld().getSpawnLocation();
                p.teleportAsync(loc).thenAccept(ok -> { if (ok) tell(p, "&fZum Spawn teleportiert."); });
            }
            case "setspawn" -> {
                if (!p.hasPermission("neptun.setspawn")) { tell(p, "&cKeine Berechtigung."); return true; }
                getConfig().set("spawns." + p.getWorld().getName(), p.getLocation());
                saveConfig(); tell(p, "&fSpawn für &l" + p.getWorld().getName() + " &fgesetzt.");
            }
            case "sethome" -> {
                if (args.length != 1) { tell(p, "&fVerwendung: &8/sethome <name>"); return true; }
                String name = cleanName(args[0]);
                Map<String, Location> map = homes.computeIfAbsent(p.getUniqueId(), k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
                int limit = homeLimit(p);
                if (!map.containsKey(name) && map.size() >= limit) { tell(p, "&cDu hast dein Home-Limit erreicht (&f" + limit + "&c)."); return true; }
                map.put(name, p.getLocation()); saveHomes();
                tell(p, "&fHome &8&l" + name + " &fgespeichert.");
            }
            case "home" -> {
                if (args.length > 0) teleportHome(p, args[0]); else openHomes(p, false);
            }
            case "delhome" -> {
                if (args.length > 0) {
                    Map<String, Location> map = homes.get(p.getUniqueId());
                    if (map != null && map.remove(args[0]) != null) { saveHomes(); tell(p, "&fHome gelöscht."); }
                    else tell(p, "&cDieses Home gibt es nicht.");
                } else openHomes(p, true);
            }
            case "rtp" -> openRtp(p);
        }
        return true;
    }

    private String cleanName(String n) {
        String s = n.replaceAll("[^A-Za-z0-9_-]", "");
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
    private int homeLimit(Player p) {
        String[] ranks = {"neptun","lunar","astro","astat","titan","platin","member"};
        for (String rank : ranks) if (p.hasPermission("neptun.rank." + rank))
            return getConfig().getInt("homes.limits." + rank, 3);
        return getConfig().getInt("homes.default-limit", 3);
    }
    private void teleportHome(Player p, String name) {
        Map<String, Location> map = homes.get(p.getUniqueId());
        Location loc = map == null ? null : map.get(name);
        if (loc == null || loc.getWorld() == null) { tell(p, "&cHome nicht gefunden."); return; }
        p.closeInventory();
        p.teleportAsync(loc).thenAccept(ok -> { if (ok) tell(p, "&fZum Home &8&l" + name + " &fteleportiert."); });
    }
    private void openHomes(Player p, boolean delete) {
        Map<String, Location> map = homes.getOrDefault(p.getUniqueId(), Collections.emptyMap());
        Inventory inv = Bukkit.createInventory(null, 54, color(delete ? "&8&lHome löschen" : getConfig().getString("homes-menu.title", "&8&lDeine Homes")));
        int slot = 0;
        for (String name : map.keySet()) {
            if (slot >= inv.getSize()) break;
            ItemStack item = new ItemStack(delete ? Material.BARRIER : Material.ENDER_PEARL);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color("&f&l" + name));
            meta.setLore(List.of(color(delete ? "&8Klicken zum Löschen" : "&8Klicken zum Teleportieren")));
            item.setItemMeta(meta); inv.setItem(slot++, item);
        }
        if (map.isEmpty()) inv.setItem(22, named(Material.PAPER, "&f&lKeine Homes", "&8Nutze &f/sethome <name>"));
        p.openInventory(inv);
        p.setMetadata("neptun_delete_menu", new org.bukkit.metadata.FixedMetadataValue(host(), delete));
    }
    private void openRtp(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, color(getConfig().getString("rtp-menu.title", "&8&lZufalls-Teleport")));
        for (int i=0;i<inv.getSize();i++) inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, "&8 "));
        int slot=11;
        for (World w : Bukkit.getWorlds()) {
            if (!getConfig().getBoolean("rtp.worlds." + w.getName() + ".enabled", w.getEnvironment() == World.Environment.NORMAL)) continue;
            inv.setItem(slot++, named(w.getEnvironment()==World.Environment.NETHER ? Material.NETHERRACK :
                w.getEnvironment()==World.Environment.THE_END ? Material.END_STONE : Material.GRASS_BLOCK,
                "&f&l" + w.getName(), "&8Klicken für Zufalls-Teleport"));
            if (slot==16) break;
        }
        p.openInventory(inv);
    }
    private ItemStack named(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat); ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(this::color).toList());
        it.setItemMeta(meta); return it;
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title.equals(color(getConfig().getString("homes-menu.title", "&8&lDeine Homes"))) ||
            title.equals(color("&8&lHome löschen"))) {
            e.setCancelled(true);
            if (e.getCurrentItem()==null || !e.getCurrentItem().hasItemMeta()) return;
            String name = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());
            if (name == null || name.isBlank() || name.equals("Keine Homes")) return;
            boolean delete = p.hasMetadata("neptun_delete_menu") && p.getMetadata("neptun_delete_menu").get(0).asBoolean();
            if (delete) {
                Map<String,Location> map=homes.get(p.getUniqueId());
                if (map!=null && map.remove(name)!=null) { saveHomes(); tell(p, "&fHome &8&l"+name+" &fgelöscht."); }
                openHomes(p,true);
            } else teleportHome(p,name);
        } else if (title.equals(color(getConfig().getString("rtp-menu.title", "&8&lZufalls-Teleport")))) {
            e.setCancelled(true);
            if (e.getCurrentItem()==null || !e.getCurrentItem().hasItemMeta()) return;
            String worldName=ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());
            World w=Bukkit.getWorld(worldName);
            if (w!=null) startRtp(p,w);
        }
    }
    private String effectiveRank(Player p) {
        for (String r : List.of("neptun","lunar","astro","astat","titan","platin","member"))
            if (p.hasPermission("neptun.rank."+r)) return r;
        return "member";
    }
    private void startRtp(Player p, World w) {
        if ((w.getEnvironment()==World.Environment.NETHER && getConfig().getBoolean("dimensions.disable-nether")) ||
            (w.getEnvironment()==World.Environment.THE_END && getConfig().getBoolean("dimensions.disable-end"))) {
            if (!p.hasPermission("neptun.bypass.dimension")) { tell(p, "&cDiese Dimension ist deaktiviert."); return; }
        }
        String rank=effectiveRank(p);
        String path="rtp.ranks."+rank;
        int min=getConfig().getInt(path+".min-radius",500), max=getConfig().getInt(path+".max-radius",10000);
        long cooldown=getConfig().getLong(path+".cooldown",360);
        long now=System.currentTimeMillis(), until=rtpCooldowns.getOrDefault(p.getUniqueId(),0L);
        if (!p.hasPermission("neptun.bypass.cooldown") && until>now) {
            tell(p, "&cBitte warte noch &f"+((until-now+999)/1000)+"s&c."); return;
        }
        p.closeInventory();
        tell(p,"&fSuche einen sicheren Ort...");
        int attempts=getConfig().getInt("rtp.max-attempts",40);
        for(int i=0;i<attempts;i++) {
            int x=ThreadLocalRandom.current().nextInt(-max,max+1);
            int z=ThreadLocalRandom.current().nextInt(-max,max+1);
            if (Math.abs(x)<min && Math.abs(z)<min) continue;
            int y=w.getHighestBlockYAt(x,z)+1;
            Location loc=new Location(w,x+0.5,y,z+0.5);
            Block floor=w.getBlockAt(x,y-1,z);
            if (!floor.getType().isSolid() || floor.isLiquid() || w.getBlockAt(x,y,z).getType().isSolid() ||
                w.getBlockAt(x,y+1,z).getType().isSolid()) continue;
            if (w.getEnvironment()==World.Environment.NORMAL && (floor.getType()==Material.LAVA || floor.getType()==Material.MAGMA_BLOCK)) continue;
            rtpCooldowns.put(p.getUniqueId(), now+cooldown*1000L);
            p.teleportAsync(loc).thenAccept(ok -> { if(ok) { p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT,1,1); p.spawnParticle(Particle.PORTAL,p.getLocation(),24); tell(p,"&fZufällig teleportiert."); } });
            return;
        }
        tell(p,"&cKein sicherer Ort gefunden. Versuche es erneut.");
    }
    @EventHandler public void onPortal(PlayerPortalEvent e) {
        if (e.getPlayer().hasPermission("neptun.bypass.dimension")) return;
        if (e.getTo()==null) return;
        World.Environment env=e.getTo().getWorld().getEnvironment();
        if (env==World.Environment.NETHER && getConfig().getBoolean("dimensions.disable-nether")) { e.setCancelled(true); tell(e.getPlayer(),"&cDer Nether ist deaktiviert."); }
        if (env==World.Environment.THE_END && getConfig().getBoolean("dimensions.disable-end")) { e.setCancelled(true); tell(e.getPlayer(),"&cDas End ist deaktiviert."); }
    }
    private void loadHomes() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        var cfg = new org.bukkit.configuration.file.YamlConfiguration();
        try { cfg.load(new java.io.File(getDataFolder(),"homes.yml")); }
        catch (Exception ignored) { }
        for(String uuid : cfg.getKeys(false)) {
            Map<String,Location> map=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            var sec=cfg.getConfigurationSection(uuid);
            if(sec==null) continue;
            for(String name:sec.getKeys(false)) {
                Location loc=cfg.getLocation(uuid+"."+name);
                if(loc!=null) map.put(name,loc);
            }
            homes.put(UUID.fromString(uuid),map);
        }
    }
    private void saveHomes() {
        var cfg = new org.bukkit.configuration.file.YamlConfiguration();
        for(var entry:homes.entrySet()) for(var home:entry.getValue().entrySet())
            cfg.set(entry.getKey()+"."+home.getKey(),home.getValue());
        try { cfg.save(new java.io.File(getDataFolder(),"homes.yml")); }
        catch(Exception e) { getLogger().warning("Could not save homes: "+e.getMessage()); }
    }
}
