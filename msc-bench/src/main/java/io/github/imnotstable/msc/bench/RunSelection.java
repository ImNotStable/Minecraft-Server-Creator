package io.github.imnotstable.msc.bench;

import io.github.imnotstable.msc.bench.task.TaskDef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a command works on, resolved and checked before anything is created: the tasks of {@code --tasks} (or every
 * task) and the modes of {@code --modes} (or every mode {@code bench.json} defines). Every way a selection can end up
 * empty or wrong is refused here with an {@link IllegalArgumentException} naming what was empty or wrong, which the
 * command line turns into exit code 2: a tasks folder without tasks, a list without names or with an empty one
 * ({@code --tasks ,}, {@code --modes a,,b}), an unknown name and a name listed twice. These rules live only here:
 * {@link io.github.imnotstable.msc.bench.run.BenchRunner#run} runs a selection as given.
 *
 * @param tasks the tasks to run, never empty
 * @param modes the modes to run each task in, never empty
 */
public record RunSelection(List<TaskDef> tasks, List<String> modes) {

    public RunSelection {
        tasks = List.copyOf(tasks);
        modes = List.copyOf(modes);
    }

    /**
     * The selection of {@code run}: the tasks {@code tasksValue} names from {@code all} (every task when null) and the
     * modes {@code modesValue} names from {@code configured} (every one, sorted, when null). {@code configured} is
     * never empty: a config refuses an empty {@code modes} and defaults an absent one to {@code baseline}.
     */
    public static RunSelection resolve(Path tasksDir, List<TaskDef> all, String tasksValue, Collection<String> configured,
            Path configFile, String modesValue) {
        List<TaskDef> tasks = tasks(tasksDir, all, tasksValue);
        List<String> modes;
        if (modesValue == null) {
            modes = configured.stream().sorted().toList();
        } else {
            modes = names("--modes", modesValue, "mode");
            for (String mode : modes) {
                if (!configured.contains(mode)) {
                    throw new IllegalArgumentException("--modes: no mode " + mode + "; " + configFile + " defines "
                            + configured.stream().sorted().toList());
                }
            }
        }
        return new RunSelection(tasks, modes);
    }

    /** All tasks of the folder, refused when it holds none. */
    public static List<TaskDef> all(Path tasksDir, List<TaskDef> all) {
        if (all.isEmpty()) {
            throw new IllegalArgumentException("no tasks in " + tasksDir.toAbsolutePath() + ": a task is a folder with "
                    + "a task.json; give --tasks-dir the bench's tasks folder");
        }
        return all;
    }

    /** The one task {@code id} names, as {@code check} takes it. */
    public static TaskDef one(Path tasksDir, List<TaskDef> all, String id) {
        List<TaskDef> picked = pick(tasksDir, all, names("the task", id, "task"), "the task");
        if (picked.size() != 1) {
            throw new IllegalArgumentException("check takes one task, not " + picked.size() + " (" + id + ")");
        }
        return picked.getFirst();
    }

    /** The tasks {@code value} names from {@code all}, or all of them when it is null. */
    static List<TaskDef> tasks(Path tasksDir, List<TaskDef> all, String value) {
        all(tasksDir, all);
        return value == null ? all : pick(tasksDir, all, names("--tasks", value, "task"), "--tasks");
    }

    private static List<TaskDef> pick(Path tasksDir, List<TaskDef> all, List<String> ids, String what) {
        all(tasksDir, all);
        List<TaskDef> picked = new ArrayList<>();
        for (String id : ids) {
            picked.add(all.stream().filter(t -> t.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(what + ": no task " + id + " in "
                            + tasksDir.toAbsolutePath() + "; `list` shows the tasks")));
        }
        return picked;
    }

    /**
     * The comma-separated names of {@code value}, without surrounding spaces; refuses a value that names nothing, an
     * empty name and a name listed twice.
     */
    static List<String> names(String flag, String value, String kind) {
        List<String> names = Arrays.stream(value.split(",", -1)).map(String::strip).toList();
        if (names.stream().allMatch(String::isEmpty)) {
            throw new IllegalArgumentException(flag + " " + UserJson.quoted(value) + " names no " + kind);
        }
        if (names.contains("")) {
            throw new IllegalArgumentException(flag + " " + UserJson.quoted(value) + " has an empty " + kind + " name");
        }
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException(flag + ": " + kind + " " + UserJson.quoted(name) + " is listed twice; use "
                        + "--repeat N for more attempts of each task and mode");
            }
        }
        return names;
    }
}
