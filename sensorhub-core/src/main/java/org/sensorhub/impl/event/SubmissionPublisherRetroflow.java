/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */
package org.sensorhub.impl.event;

import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.BiConsumer;

/**
 * Compatibility wrapper extending JDK {@link SubmissionPublisher}.
 *
 * <p>The original Java 8 retroport embedded {@code sun.misc.Unsafe} memory-access
 * methods that are deprecated for removal in JDK 25+. On Java 9+, the standard
 * {@link java.util.concurrent.SubmissionPublisher} provides the identical API
 * backed by {@code VarHandle} without {@code sun.misc.Unsafe}.</p>
 *
 * @param <T> the published item type
 */
public class SubmissionPublisherRetroflow<T> extends SubmissionPublisher<T> {

    public SubmissionPublisherRetroflow(Executor executor, int maxBufferCapacity,
                                        BiConsumer<? super Subscriber<? super T>, ? super Throwable> handler) {
        super(executor, maxBufferCapacity, handler);
    }

    public SubmissionPublisherRetroflow(Executor executor, int maxBufferCapacity) {
        super(executor, maxBufferCapacity);
    }

    public SubmissionPublisherRetroflow() {
        super();
    }
}
