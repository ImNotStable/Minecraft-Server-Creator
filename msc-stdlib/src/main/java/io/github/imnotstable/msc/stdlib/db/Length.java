package io.github.imnotstable.msc.stdlib.db;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The longest a {@link Repository} text column may be. Text keys and indexed text are {@code VARCHAR(255)} unless this
 * says otherwise; other text is unlimited.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Length {
    int value();
}
