package bench.sprint;

import java.util.List;

record GameConfig(List<Stage> stages) {
    static GameConfig game() {
        throw new UnsupportedOperationException();
    }
}
