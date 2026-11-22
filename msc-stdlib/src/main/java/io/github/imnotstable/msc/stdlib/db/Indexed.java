package io.github.imnotstable.msc.stdlib.db;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A {@link Repository} column with an index, for fields you search or sort by. {@code unique} also forbids repeats. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Indexed {
    boolean unique() default false;
}
