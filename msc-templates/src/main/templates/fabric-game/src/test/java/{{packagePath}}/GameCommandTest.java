package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real {@code /game} command tree without a server: use this kind of test for commands. Code that needs
 * players and a world (joining, goals, respawns) is checked on the dev server: start it, join with the test player,
 * and use {@code game status}.
 */
class GameCommandTest {
    @TempDir
    Path dir;

    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void bootstrap() {
        // Command classes touch Minecraft's registries, which must be loaded first.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void register() {
        dispatcher = new CommandDispatcher<>();
        Game game = new Game(TestGames.twoStages(), new Progress(dir.resolve("progress.properties")), () -> {
        });
        new GameCommand(game).register(dispatcher);
    }

    /** A command source that keeps every message it is sent. */
    private static final class Recorder implements CommandSource {
        final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static CommandSourceStack source(Recorder recorder, PermissionSet permissions) {
        return new CommandSourceStack(recorder, Vec3.ZERO, Vec2.ZERO, null, permissions, Component.literal("Server"), null);
    }

    @Test
    void statusReportsTheGame() throws CommandSyntaxException {
        Recorder console = new Recorder();

        int result = dispatcher.execute("game status", source(console, LevelBasedPermissionSet.OWNER));

        assertThat(result).isEqualTo(1);
        // Only the start of the line: state you add to the status does not break it.
        assertThat(console.messages).first().asString().startsWith("game: 2 stages, time 6000, normal, 0 online");
    }

    @Test
    void explainsMistakes() throws CommandSyntaxException {
        Recorder console = new Recorder();
        CommandSourceStack source = source(console, LevelBasedPermissionSet.OWNER);

        dispatcher.execute("game", source);
        dispatcher.execute("game stage lava Steve", source);
        dispatcher.execute("game next", source);

        assertThat(console.messages).containsExactly(GameCommand.USAGE,
                "no stage lava. Stages: [start, arena]", "name a player: next <player>");
    }

    /** Words are split on runs of spaces, and tab completion offers stage names after them (as every game template). */
    @Test
    void extraSpacesBetweenWordsAreIgnored() throws CommandSyntaxException {
        Recorder console = new Recorder();
        CommandSourceStack source = source(console, LevelBasedPermissionSet.OWNER);

        dispatcher.execute("game  stage   lava  Steve", source);

        assertThat(console.messages).containsExactly("no stage lava. Stages: [start, arena]");
        assertThat(dispatcher.getCompletionSuggestions(dispatcher.parse("game stage  ", source)).join().getList())
                .extracting(Suggestion::getText).containsExactly("arena", "start");
        assertThat(dispatcher.getCompletionSuggestions(dispatcher.parse("game stage   ar", source)).join().getList())
                .extracting(Suggestion::getText).containsExactly("arena");
    }

    @Test
    void needsOperatorPermission() {
        Recorder player = new Recorder();

        assertThatThrownBy(() -> dispatcher.execute("game status", source(player, PermissionSet.NO_PERMISSIONS)))
                .isInstanceOf(CommandSyntaxException.class);
    }
}
