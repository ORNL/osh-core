package org.sensorhub.impl.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventListener;

public class TestBasicEventHandlerDeadlock
{
    private static final class NumberedEvent extends Event
    {
        private final int num;

        NumberedEvent(int num)
        {
            this.timeStamp = System.currentTimeMillis();
            this.source = "test";
            this.num = num;
        }

        @Override
        public String getSourceID()
        {
            return "urn:test:source";
        }
    }

    @Test
    public void testPublishDoesNotHoldMonitorAcrossCallbacksPreventingLockInversion() throws Exception
    {
        final Object stateLock = new Object();
        final BasicEventHandler eventHandler = new BasicEventHandler();
        final CountDownLatch inCallback = new CountDownLatch(1);
        final CountDownLatch stateLockHeld = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean(false);

        IEventListener reentrantUiListener = e -> {
            inCallback.countDown();
            try
            {
                assertTrue(stateLockHeld.await(5, TimeUnit.SECONDS));
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
            }
            synchronized (stateLock)
            {
                eventHandler.registerListener(ev -> {});
                completed.set(true);
            }
        };
        eventHandler.registerListener(reentrantUiListener);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> publisher = pool.submit(() -> eventHandler.publish(new NumberedEvent(1)));
            assertTrue(inCallback.await(5, TimeUnit.SECONDS));

            Future<?> registrar = pool.submit(() -> {
                synchronized (stateLock)
                {
                    stateLockHeld.countDown();
                    eventHandler.registerListener(ev -> {});
                }
            });

            registrar.get(5, TimeUnit.SECONDS);
            publisher.get(5, TimeUnit.SECONDS);
            assertTrue(completed.get());
            assertEquals(3, eventHandler.getNumListeners());
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    @Test
    public void testRecursivePublishPreservesFifoOrder()
    {
        final BasicEventHandler eventHandler = new BasicEventHandler();
        final List<Integer> order = new ArrayList<>();

        eventHandler.registerListener(e -> {
            int num = ((NumberedEvent)e).num;
            order.add(num);
            if (num == 1)
                eventHandler.publish(new NumberedEvent(2));
        });
        eventHandler.registerListener(e -> order.add(((NumberedEvent)e).num * 10));

        eventHandler.publish(new NumberedEvent(1));
        assertEquals(List.of(1, 10, 2, 20), order);
    }
}
