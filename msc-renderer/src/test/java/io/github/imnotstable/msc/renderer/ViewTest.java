package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ViewTest {
    @Test
    void parsesNamedViews() {
        assertThat(View.parse("top")).isEqualTo(new View.Ortho(View.Side.TOP));
        assertThat(View.parse(" West ")).isEqualTo(new View.Ortho(View.Side.WEST));
        assertThat(View.parse("iso")).isEqualTo(new View.Isometric(View.Corner.SOUTH_EAST));
        assertThat(View.parse("iso-nw")).isEqualTo(new View.Isometric(View.Corner.NORTH_WEST));
    }

    @Test
    void parsesCameras() {
        assertThat(View.parse("eye 1,65.5,-3 yaw 90 pitch 10 fov 60")).isEqualTo(new View.Camera(1, 65.5, -3, 90, 10, 60));
        View.Camera north = (View.Camera) View.parse("eye 0,64,10 look 0,64,0");
        assertThat(Math.abs(north.yaw())).isCloseTo(180, within(1e-9));
        assertThat(north.pitch()).isCloseTo(0, within(1e-9));
        assertThat(north.fov()).isEqualTo(70);
        View.Camera down = (View.Camera) View.parse("eye 0,70,0 look 0,60,0");
        assertThat(down.pitch()).isCloseTo(90, within(1e-9));
        View.Camera west = (View.Camera) View.parse("eye 5,64,0 look 0,64,0");
        assertThat(west.yaw()).isCloseTo(90, within(1e-9));
    }

    @Test
    void badViewsListTheAcceptedForms() {
        for (String bad : new String[] {"", "sideways", "eye 1,2", "eye 1,2,3 look 1,2,3", "eye 1,2,3 fov 500", "eye 1,2,3 zoom 2"}) {
            assertThatThrownBy(() -> View.parse(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("use top, north").hasMessageContaining("eye x,y,z look x,y,z");
        }
    }
}
