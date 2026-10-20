package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import de.coinshop.ExpeditionManager;
import de.coinshop.ItemUtil;
import de.coinshop.NpcDuels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Duelle: gegen Spieler (mit Auswahlmenü) oder gegen NPCs. */
public final class DuelMenu extends Menu {

    public DuelMenu(CoinShopPlugin plugin, Player viewer) {
        super(plugin, 4, Component.text("Duelle"));
        ExpeditionManager ex = plugin.expeditions();
        UUID uuid = viewer.getUniqueId();

        set(11, ItemUtil.icon(Material.PLAYER_HEAD, ExpeditionMenu.line("Spieler herausfordern", NamedTextColor.GOLD), List.of(
                ExpeditionMenu.line("Wähle einen Spieler und optional einen Einsatz.", NamedTextColor.GRAY),
                ExpeditionMenu.line("Beide sehen den Kampf der Begleiter in einer Arena.", NamedTextColor.GRAY),
                ExpeditionMenu.line("Gruppenstärke: " + Math.round(ex.duel().strength(uuid)), NamedTextColor.YELLOW),
                ExpeditionMenu.line("Klicken: Spieler wählen", NamedTextColor.GREEN))),
                (p, c) -> new DuelChallengeMenu(plugin, p, 0).open(p));

        set(15, ItemUtil.icon(Material.IRON_SWORD, ExpeditionMenu.line("Duelle gegen NPCs", NamedTextColor.GOLD), List.of(
                ExpeditionMenu.line("Rangleiter, tägliches Turnier, Einsatz, Risikokampf.", NamedTextColor.GRAY),
                ExpeditionMenu.line("Fortschritt: " + ex.npc().progress(uuid) + " von " + NpcDuels.NPCS.size() + " besiegt", NamedTextColor.YELLOW),
                ExpeditionMenu.line("Klicken: öffnen", NamedTextColor.GREEN))),
                (p, c) -> new NpcDuelMenu(plugin, p).open(p));

        set(27, backButton(), (p, c) -> new AdventureMenu(plugin, p).open(p));
        fillEmpty();
    }

    @Override
    public void open(Player player) {
        set(35, balanceIcon(player), incomeClick());
        super.open(player);
    }
}
