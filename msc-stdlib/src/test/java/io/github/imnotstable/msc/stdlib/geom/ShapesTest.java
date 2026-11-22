package io.github.imnotstable.msc.stdlib.geom;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShapesTest {
    @Test
    void shapesHaveTheirSizeAndRadius() {
        assertThat(Shapes.circle(2, 16)).hasSize(16).allSatisfy(p -> assertThat(p.length()).isCloseTo(2, within(1e-9)));
        assertThat(Shapes.sphere(3, 50)).hasSize(50).allSatisfy(p -> assertThat(p.length()).isCloseTo(3, within(1e-9)));
        assertThat(Shapes.line(Vec3.ZERO, new Vec3(4, 0, 0), 5)).extracting(Vec3::x).containsExactly(0.0, 1.0, 2.0, 3.0, 4.0);
        assertThat(Shapes.helix(1, 4, 2, 9).getLast().y()).isEqualTo(4);
        assertThat(Shapes.cuboidEdges(Vec3.ZERO, new Vec3(1, 1, 1), 1)).hasSize(24);
        assertThat(Shapes.star(2, 1, 5, 4)).hasSize(40);
        assertThat(Shapes.randomInSphere(2, 30, new Random(1))).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(2));
        assertThat(new Vec3(1, 0, 0).rotateY(Math.PI / 2).z()).isCloseTo(-1, within(1e-9));
    }
}
