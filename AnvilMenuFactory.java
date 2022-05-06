import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * <p>
 * Make anvil menus!
 * </p>
 *
 * Example:
 * <pre>{@code
 * AnvilMenuFactory factory = new AnvilMenuFactory(plugin);
 * AnvilMenuFactory.Menu menu = factory.newMenu("What is your name?", "Jacob", (player, closeReason, itemName) -> {
 * 	if(closeReason == CloseReason.CLICK) { // Player clicked the 3rd slot in the anvil
 * 		player.sendMessage("Your name is " + itemName);
 * 		return Result.CLOSE; // close the anvil menu
 *        } else if(closeReason == CloseReason.CLIENT_CLOSE) { // Player closed the menu
 * 		player.sendMessage("You closed the menu! :(");
 * 		return Result.REOPEN_WITH_TEXT; // reopen the anvil menu with the text they typed before
 * 		// replace with Result.REOPEN if you don't want to keep what they typed before
 *    } else if(closeReason == CloseReason.SERVER_CLOSE) {
 * 		player.sendMessage("Some other plugin closed the menu!");
 *    } else if(closeReason == CloseReason.DISCONNECT) {
 * 		// player disconnected
 *    }
 *
 *    return Result.CLOSE;
 * });
 * menu.open(player);
 * }</pre>
 *
 * @author Jacob
 */
public final class AnvilMenuFactory {

	private static final ProtocolManager protocolManager =
			ProtocolLibrary.getProtocolManager();

	private final Map<Player, Menu> menus = new ConcurrentHashMap<>();
	private final Plugin plugin;

	/**
	 * Makes a new anvil menu factory
	 *
	 * @param plugin plugin instance
	 */
	public AnvilMenuFactory(Plugin plugin) {
		Validate.notNull(plugin, "plugin is null");
		Validate.isTrue(plugin.isEnabled(), "plugin is not enabled");

		this.plugin = plugin;
		listen();
	}

	private void listen() {
		Bukkit.getServer().getPluginManager().registerEvents(new Listener() {
			@EventHandler
			public void onQuit(PlayerQuitEvent event) {
				Player p = event.getPlayer();

				Menu menu = menus.remove(p);
				if(menu != null) {
					try {
						menu.itemNames.remove(p);
						menu.dontCall.remove(p);
						menu.getResponse().execute(p, CloseReason.DISCONNECT, menu.itemNames.remove(p));
					} catch(Throwable t) {
						handleError(t);
					}
				}
			}

			@EventHandler
			public void onClick(InventoryClickEvent event) {
				if(!(event.getWhoClicked() instanceof Player p))
					return;

				Menu menu = menus.get(p);

				if(menu == null)
					return;

				event.setCancelled(true);

				if(event.getSlot() != 2)
					return;

				execute(p, CloseReason.CLICK);
			}

		}, plugin);

		protocolManager.addPacketListener(new PacketAdapter(plugin, PacketType.Play.Server.CLOSE_WINDOW,
				PacketType.Play.Client.CLOSE_WINDOW, PacketType.Play.Client.ITEM_NAME) {
			public void onPacketReceiving(PacketEvent event) {
				Player p = event.getPlayer();

				Menu menu = menus.get(p);
				if(menu == null)
					return;

				if(event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
					doSync(() -> execute(p, CloseReason.CLIENT_CLOSE));
				} else {
					event.setCancelled(true);
					String newItemName = event.getPacket().getStrings().read(0);

					menu.itemNames.put(p, newItemName == null ? "" :
							newItemName);
				}
			}

			public void onPacketSending(PacketEvent event) {
				execute(event.getPlayer(), CloseReason.SERVER_CLOSE);
			}
		});
	}

	private void handleError(Throwable t) {
		plugin.getLogger().log(Level.WARNING, "Anvil callback error", t);
	}

	private void execute(Player p, CloseReason reason) {
		Menu menu = menus.remove(p);

		if(menu == null) {
			return;
		}

		if(menu.dontCall.contains(p)) {
			menu.dontCall.remove(p);
			return;
		}

		doSync(() -> {
			try {
				String itemName = menu.itemNames.remove(p);

				if(menu.stripColor())
					itemName = ChatColor.stripColor(itemName);

				Result result = menu.getResponse().execute(p, reason, itemName);

				if(result == Result.REOPEN) {
					menu.open(p);
				} else if(result == Result.REOPEN_WITH_TEXT) {
					menu.open(p, itemName);
				} else
					p.closeInventory();
			} catch(Throwable t) {
				handleError(t);
			}
		});
	}

	private void doSync(Runnable run) {
		if(Bukkit.isPrimaryThread())
			run.run();
		else
			new BukkitRunnable() {
				public void run() {
					run.run();
				}
			}.runTask(plugin);
	}

	/**
	 * Gets the plugin for this factory
	 *
	 * @return plugin for this factory
	 */
	public Plugin getPlugin() {
		return plugin;
	}

	/**
	 * Makes a new menu
	 *
	 * @return created menu
	 */
	public Menu newMenu() {
		return new Menu(null, null, null);
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param title anvil title
	 * @return created menu
	 */
	public Menu newMenu(String title) {
		return new Menu(title, null, null);
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param item item to show
	 * @return created menu
	 */
	public Menu newMenu(ItemStack item) {
		return new Menu(null, item, null);
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param title anvil title
	 * @param item item to show
	 * @return created menu
	 */
	public Menu newMenu(String title, ItemStack item) {
		return new Menu(title, item, null);
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param title anvil title
	 * @param response response callback
	 * @return created menu
	 */
	public Menu newMenu(String title, AnvilResponse response) {
		return new Menu(title, null, response);
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param item item to show
	 * @param response response callback
	 * @return created menu
	 */
	public Menu newMenu(ItemStack item, AnvilResponse response) {
		return new Menu(null, item, response);
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param title anvil title
	 * @param item item to show
	 * @param response response callback
	 * @return created menu
	 */
	public Menu newMenu(String title, ItemStack item, AnvilResponse response) {
		return new Menu(title, item, response);
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param title anvil title
	 * @param itemName default item name
	 * @param response response callback
	 * @return created menu
	 */
	public Menu newMenu(String title, String itemName, AnvilResponse response) {
		Menu menu = newMenu(title, itemName);

		menu.setResponse(response);

		return menu;
	}

	/**
	 * Makes a new menu with the specified parameters
	 *
	 * @param title anvil title
	 * @param itemName default item name
	 * @return created menu
	 */
	public Menu newMenu(String title, String itemName) {
		Menu menu = new Menu(title, null, null);

		menu.setItemName(itemName);

		return menu;
	}

	/**
	 * Represents an anvil response callback
	 */
	@FunctionalInterface
	public interface AnvilResponse {
		/**
		 * Execute response
		 *
		 * @param player player
		 * @param reason reason menu was closed
		 * @param itemName final item name
		 * @return what should happen after
		 */
		Result execute(Player player, CloseReason reason, String itemName);
	}

	/**
	 * Reasons for why the anvil menu was closed
	 */
	public enum CloseReason {
		/**
		 * Item was clicked
		 */
		CLICK,
		/**
		 * Player disconnected
		 */
		DISCONNECT,
		/**
		 * Server closed the menu
		 */
		SERVER_CLOSE,
		/**
		 * Player closed the menu
		 */
		CLIENT_CLOSE
	}

	/**
	 * Represents what should happen after the response callback
	 */
	public enum Result {
		/**
		 * Close the menu
		 */
		CLOSE,
		/**
		 * Reopen the menu with the default item name
		 */
		REOPEN,
		/**
		 * Reopen the menu with the previously inputted item name
		 */
		REOPEN_WITH_TEXT
	}

	/**
	 * Represents an anvil menu.
	 * A single instance can be used for multiple players
	 */
	public final class Menu {
		private static final AnvilResponse DEFAULT_RESPONSE = (a, b, c) -> Result.CLOSE;

		private final Map<Player, String> itemNames = new ConcurrentHashMap<>();
		private final Map<Player, String> unmodifiableItemNames =
				Collections.unmodifiableMap(itemNames);
		private final List<Player> dontCall =
				Collections.synchronizedList(new ArrayList<>());

		private String title = null;
		private ItemStack item = new ItemStack(Material.PAPER);
		private AnvilResponse response;

		private boolean stripColor = true;

		private Menu(String title, ItemStack item, AnvilResponse response) {
			setTitle(title);
			setItem(item);
			setResponse(response);
		}

		/**
		 * Gets the title of the anvil menu
		 *
		 * @return title of the anvil menu
		 */
		public String getTitle() {
			return title;
		}

		/**
		 * Sets the title of the anvil menu. Setting the title to null will
		 * set it to the default
		 *
		 * @param title new title
		 */
		public void setTitle(String title) {
			this.title = title;
		}

		/**
		 * Gets the item in the anvil menu
		 *
		 * @return item in the anvil
		 */
		public ItemStack getItem() {
			return item;
		}

		/**
		 * Sets the item in the anvil menu.
		 * Does not apply to already opened menus unless
		 * {@link Menu#update(Player)} is called
		 *
		 * @param item new item
		 */
		public void setItem(ItemStack item) {
			this.item = item == null ? new ItemStack(Material.PAPER) : item;
		}

		/**
		 * Gets the default item name, can be null
		 *
		 * @return default item name
		 */
		public String getItemName() {
			ItemMeta meta = item.getItemMeta();

			if(meta == null)
				return null;

			return meta.getDisplayName();
		}

		/**
		 * Sets the default item name
		 *
		 * @param name New item name
		 */
		public void setItemName(String name) {
			ItemStack item = getItemAsCopy();
			ItemMeta meta = item.getItemMeta();

			if(meta != null) {
				meta.setDisplayName(name);
				item.setItemMeta(meta);
			}
			this.item = item;
		}

		/**
		 * Gets the item in this menu as a clone
		 *
		 * @return clone of the item in this menu
		 */
		public ItemStack getItemAsCopy() {
			return item.clone();
		}

		/**
		 * Updates the menu for all viewers
		 */
		public void update() {
			getViewers().forEach(this::update);
		}

		/**
		 * Updates the menu for the specified player
		 *
		 * @param player player to update it for
		 */
		public void update(Player player) {
			if(player == null || !player.isOnline())
				return;

			if(!getViewers().contains(player))
				return;

			String itemName = itemNames.remove(player);
			close(player, false);

			ItemStack item = getItemAsCopy();

			if(itemName != null) {
				ItemMeta meta = item.getItemMeta();
				if(meta != null) {
					meta.setDisplayName(itemName);
					item.setItemMeta(meta);
				}
			}

			open(player, item);
			itemNames.put(player, itemName);
		}

		/**
		 * Closes the menu for the specified player
		 *
		 * @param player player to close it for
		 * @param call whether to call the close callback or not
		 */
		public void close(Player player, boolean call) {
			if(player == null || !player.isOnline())
				return;

			if(!getViewers().contains(player))
				return;

			if(!call) {
				dontCall.add(player);
			}

			menus.remove(player);
			doSync(player::closeInventory);
		}

		/**
		 * Opens the anvil menu to the specified player
		 *
		 * @param player player to open it for
		 * @param item item to display
		 */
		public void open(Player player, ItemStack item) {
			if(player == null || !player.isOnline())
				return;

			doSync(() -> {
				player.closeInventory();

				Inventory inv = Bukkit.createInventory(player, InventoryType.ANVIL,
						title == null ? InventoryType.ANVIL.getDefaultTitle() : title);
				inv.setItem(0, item);

				player.openInventory(inv);
				menus.put(player, this);
			});
		}

		/**
		 * Gets a list of all active viewers.
		 * Modifications to the list will result in a {@link UnsupportedOperationException}
		 *
		 * @return a list of all viewers
		 */
		public List<Player> getViewers() {
			return menus.entrySet().stream().filter(e -> e.getValue() == Menu.this)
					.map(Entry::getKey).toList();
		}

		/**
		 * Closes the menu for the specified player
		 *
		 * @param player player to close it for
		 */
		public void close(Player player) {
			close(player, true);
		}

		/**
		 * Gets the response callback for this menu
		 *
		 * @return response callback for this menu
		 */
		public AnvilResponse getResponse() {
			return response;
		}

		/**
		 * Changes the response for this menu
		 *
		 * @param response the new response for this menu
		 */
		public void setResponse(AnvilResponse response) {
			this.response = response == null ? DEFAULT_RESPONSE : response;
		}

		/**
		 * Opens the menu to the player
		 *
		 * @param player player to open it for
		 */
		public void open(Player player) {
			open(player, item);
		}

		/**
		 * Opens the anvil menu to the specified player
		 *
		 * @param player player to open it for
		 * @param itemName Default item name
		 */
		public void open(Player player, String itemName) {
			ItemStack item = getItemAsCopy();
			ItemMeta meta = item.getItemMeta();

			if(meta != null) {
				meta.setDisplayName(itemName);
				item.setItemMeta(meta);
			}

			open(player, item);
		}

		/**
		 * Gets the item names currently in the item name
		 * field of the anvil.
		 * Modifications will result in a {@link UnsupportedOperationException}
		 *
		 * @return item names currently in the item name
		 * field of the anvil
		 */
		public Map<Player, String> getItemNames() {
			return unmodifiableItemNames;
		}

		/**
		 * <p>
		 * Gets whether color codes will be stripped from the
		 * player input when being passed into the callback.
		 * </p>
		 * Default is true
		 *
		 * @return whether color codes will be stripped or not
		 */
		public boolean stripColor() {
			return stripColor;
		}

		/**
		 * <p>
		 * Sets whether color codes will be stripped from the
		 * player input when being passed into the callback.
		 * </p>
		 * Default is true
		 *
		 * @param stripColor new value
		 */
		public void setStripColor(boolean stripColor) {
			this.stripColor = stripColor;
		}
	}
}
