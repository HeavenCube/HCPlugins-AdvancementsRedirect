package fr.noltox.hcplugins.advancementsbuttonredirect.command;

import fr.noltox.hcplugins.core.api.command.CoreCommand;
import fr.noltox.hcplugins.core.api.message.CoreTranslations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.bukkit.plugin.Plugin;

/**
 * Defines the canonical {@code /hcplugins advancementsredirect} branch.
 */
public final class AdvancementsCommand implements CoreCommand {

    private static final Component RELOAD_INVALID = Component.text(
            "Configuration rechargée, mais la clé console-command est vide ou absente.",
            NamedTextColor.YELLOW
    );
    private static final Component USAGE = Component.text(
            "Utilisation : /hcplugins advancementsredirect reload",
            NamedTextColor.RED
    );

    private final BooleanSupplier configurationReloader;
    private final BooleanSupplier commandConfigured;
    private final Plugin plugin;
    private final CoreTranslations translations;

    public AdvancementsCommand(
            Plugin plugin,
            CoreTranslations translations,
            BooleanSupplier configurationReloader,
            BooleanSupplier commandConfigured
    ) {
        this.plugin = plugin;
        this.translations = translations;
        this.configurationReloader = configurationReloader;
        this.commandConfigured = commandConfigured;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            reload(source);
        } else {
            source.getSender().sendMessage(USAGE);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().isOp() || args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return "reload".startsWith(prefix) ? List.of("reload") : List.of();
    }

    private void reload(CommandSourceStack source) {
        if (!source.getSender().isOp()) {
            source.getSender().sendMessage(translations.operatorOnly());
            return;
        }
        long started = System.nanoTime();
        if (!configurationReloader.getAsBoolean()) {
            source.getSender().sendMessage(translations.reloadFailure(plugin));
            return;
        }
        source.getSender().sendMessage(commandConfigured.getAsBoolean()
                ? translations.reloadSuccess(plugin, System.nanoTime() - started) : RELOAD_INVALID);
    }
}
