package io.github.imnotstable.msc.stdlib.fabric;

import com.mojang.brigadier.CommandDispatcher;
import io.github.imnotstable.msc.stdlib.cmd.CommandTree;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Commands from a CommandTree land in the game's dispatcher with their aliases, arguments and suggestions. */
class FabricCommandsTest {
    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void treesRegisterRunWithArgumentsAndSuggest() throws Exception {
        List<String> ran = new ArrayList<>();
        Cmd.register(CommandTree.create("greet").aliases("hi")
                .sub(CommandTree.create("loud").executes(c -> ran.add("LOUD " + c.rest(0))))
                .executes(c -> ran.add("greet " + c.rest(0))));
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        Hooks.commands(dispatcher);
        CommandSourceStack console = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                PermissionSet.ALL_PERMISSIONS, Component.literal("console"), null);
        dispatcher.execute("greet hello world", console);
        dispatcher.execute("greet", console);
        dispatcher.execute("hi loud there", console);
        assertThat(ran).containsExactly("greet hello world", "greet ", "LOUD there");
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("greet lo", console)).get();
        assertThat(suggestions.getList()).extracting(s -> s.getText()).containsExactly("loud");
    }
}
