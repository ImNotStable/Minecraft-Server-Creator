package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
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

/** Runs the real command tree without a server: use this kind of test for commands. */
class HelloCommandTest {
    @BeforeAll
    static void bootstrap() {
        // Command classes touch Minecraft's registries, which must be loaded first.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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

    private static CommandSourceStack player(Recorder recorder, String name) {
        return new CommandSourceStack(recorder, Vec3.ZERO, Vec2.ZERO, null, PermissionSet.NO_PERMISSIONS,
                Component.literal(name), null);
    }

    @Test
    void helloRepliesToTheSender() throws CommandSyntaxException {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        HelloCommand.register(dispatcher, new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO));
        Recorder alex = new Recorder();

        int result = dispatcher.execute("hello", player(alex, "Alex"));

        assertThat(result).isEqualTo(1);
        assertThat(alex.messages).containsExactly("Hello, Alex!");
    }
}
