package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TemplateVariablesTest {
    @Test
    void derivesEverythingFromTheName() {
        for (String input : new String[] {"spawn lobby", "spawn-lobby", "SpawnLobby", "spawn_lobby"}) {
            TemplateVariables v = TemplateVariables.of(input);
            assertThat(v.name()).isEqualTo("SpawnLobby");
            assertThat(v.projectId()).isEqualTo("spawn-lobby");
            assertThat(v.packageName()).isEqualTo("com.example.spawnlobby");
            assertThat(v.mainClass()).isEqualTo("SpawnLobbyPlugin");
        }
    }

    @Test
    void handlesAcronymsDigitsAndPluginSuffix() {
        assertThat(TemplateVariables.of("PvP arena 2").projectId()).isEqualTo("pv-p-arena-2");
        assertThat(TemplateVariables.of("MSC tools").name()).isEqualTo("MscTools");
        assertThat(TemplateVariables.of("MyPlugin").mainClass()).isEqualTo("MyPlugin");
    }

    @Test
    void defaultsAreDevFriendly() {
        TemplateVariables v = TemplateVariables.of("x");
        assertThat(v.serverIp()).isEqualTo("127.0.0.1");
        assertThat(v.serverPort()).isEqualTo(25565);
        assertThat(v.rconPort()).isEqualTo(25575);
        assertThat(v.rconPassword()).hasSize(24).isNotEqualTo(TemplateVariables.of("x").rconPassword());
        assertThat(v.levelType()).isEqualTo("minecraft:flat");
        assertThat(v.asMap()).containsEntry("packagePath", "com/example/x");
    }

    @Test
    void withersOverrideOneValue() {
        TemplateVariables v = TemplateVariables.of("x").withPackageName("dev.me.x").withRconPort(25580);
        assertThat(v.packageName()).isEqualTo("dev.me.x");
        assertThat(v.rconPort()).isEqualTo(25580);
        assertThat(v.name()).isEqualTo("X");
    }

    @Test
    void rejectsBadValues() {
        assertThatThrownBy(() -> TemplateVariables.of("2fast")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateVariables.of("  ")).isInstanceOf(IllegalArgumentException.class);
        TemplateVariables v = TemplateVariables.of("x");
        assertThatThrownBy(() -> v.withPackageName("Com.Bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withDescription("say \"hi\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withAuthor("a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withRconPort(25565)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withServerPort(70000)).isInstanceOf(IllegalArgumentException.class);
    }
}
