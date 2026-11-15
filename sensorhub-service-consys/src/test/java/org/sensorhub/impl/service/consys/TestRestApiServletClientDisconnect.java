package org.sensorhub.impl.service.consys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.channels.ClosedChannelException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.EofException;
import org.junit.Test;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.impl.datastore.mem.InMemoryObsStore;
import org.sensorhub.impl.datastore.view.ObsStoreView;
import org.sensorhub.impl.service.consys.obs.ObsStoreWrapper;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.slf4j.LoggerFactory;
import org.vast.data.DataBlockDouble;
import org.vast.data.TextImpl;

public class TestRestApiServletClientDisconnect
{
    @Test
    public void detectsJettyEofAndBrokenPipeDisconnects()
    {
        assertTrue(RestApiServlet.isClientDisconnect(new EofException()));
        assertTrue(RestApiServlet.isClientDisconnect(new RuntimeException("wrapped", new EofException())));
        assertTrue(RestApiServlet.isClientDisconnect(new EOFException("client closed")));
        assertTrue(RestApiServlet.isClientDisconnect(new ClosedChannelException()));
        assertTrue(RestApiServlet.isClientDisconnect(new IOException("Broken pipe")));
        assertTrue(RestApiServlet.isClientDisconnect(new IOException("Connection reset by peer")));
    }

    @Test
    public void preservesGenuineServerErrors()
    {
        assertFalse(RestApiServlet.isClientDisconnect(new IllegalStateException("Datastore failure")));
        assertFalse(RestApiServlet.isClientDisconnect(new IOException("Permission denied opening archive file")));
        assertFalse(RestApiServlet.isClientDisconnect(null));
    }

    @Test
    public void purgesQueuedAsyncTasksWhenClientDisconnectsBeforeExecution() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        try
        {
            executor.submit(() -> {
                blockerStarted.countDown();
                try
                {
                    releaseBlocker.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

            TestHarnessServlet servlet = new TestHarnessServlet(executor);
            AtomicInteger executedActions = new AtomicInteger();
            List<MockAsyncHandle> handles = new ArrayList<>();

            for (int i = 0; i < 8; i++)
            {
                MockAsyncHandle handle = new MockAsyncHandle(false);
                handles.add(handle);
                servlet.invokeDispatch(handle.request, handle.response, ctx -> executedActions.incrementAndGet());
            }
            assertEquals(8, executor.getQueue().size());

            // Simulate rapid client abort on first 7 queued requests while worker is occupied
            for (int i = 0; i < 7; i++)
                handles.get(i).fireError(new EofException("aborted by client"));

            assertEquals(1, executor.getQueue().size());

            releaseBlocker.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, executedActions.get());
            assertEquals(1, handles.get(7).completeCalls.get());
        }
        finally
        {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void completesAsyncContextCleanlyOnTimeoutWithoutIllegalState() throws Exception
    {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        try
        {
            executor.submit(() -> {
                blockerStarted.countDown();
                try
                {
                    releaseBlocker.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

            TestHarnessServlet servlet = new TestHarnessServlet(executor);
            AtomicBoolean ranAfterTimeout = new AtomicBoolean(false);
            MockAsyncHandle handle = new MockAsyncHandle(true);
            servlet.invokeDispatch(handle.request, handle.response, ctx -> ranAfterTimeout.set(true));

            assertEquals(1, executor.getQueue().size());
            handle.fireTimeout();

            assertEquals(0, executor.getQueue().size());
            assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, handle.status.get());
            assertEquals(1, handle.completeCalls.get());

            releaseBlocker.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(ranAfterTimeout.get());
        }
        finally
        {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void obsStoreWrappersForwardOffsetAndMatchSkipSemantics() throws Exception
    {
        AtomicLong capturedOffset = new AtomicLong(-1L);
        InMemoryObsStore baseStore = new InMemoryObsStore(1) {
            @Override
            public Stream<Entry<BigId, IObsData>> selectEntries(ObsFilter query, Set<ObsField> fields, long offset)
            {
                capturedOffset.set(offset);
                return super.selectEntries(query, fields, offset);
            }
        };

        var dsComp = new TextImpl();
        dsComp.setName("testRecord");
        BigId dsKey = baseStore.getDataStreams().add(new org.sensorhub.api.data.DataStreamInfo.Builder()
            .withSystem(new org.sensorhub.api.feature.FeatureId(BigId.fromLong(1, 10L), "urn:test:sys"))
            .withName("alarmStream")
            .withRecordDescription(dsComp)
            .withRecordEncoding(new org.vast.data.TextEncodingImpl())
            .build()).getInternalID();

        Instant t0 = Instant.parse("2026-11-15T00:00:00Z");
        for (int i = 0; i < 60; i++)
        {
            DataBlockDouble block = new DataBlockDouble(1);
            block.setDoubleValue(i);
            baseStore.add(new ObsData.Builder()
                .withDataStream(dsKey)
                .withPhenomenonTime(t0.plusSeconds(i))
                .withResult(block)
                .build());
        }

        ObsStoreView view = new ObsStoreView(baseStore, new ObsFilter.Builder().build());
        ObsStoreWrapper wrapper = new ObsStoreWrapper(view, baseStore);

        for (long offset : new long[] { 0L, 5L, 15L, 50L })
        {
            ObsFilter filter = new ObsFilter.Builder()
                .withLimit(offset + 16L)
                .build();

            List<BigId> expected = wrapper.selectEntries(filter)
                .skip(offset)
                .limit(16L)
                .map(Entry::getKey)
                .collect(Collectors.toList());

            capturedOffset.set(-1L);
            List<BigId> actual = wrapper.selectEntries(filter, offset)
                .limit(16L)
                .map(Entry::getKey)
                .collect(Collectors.toList());

            assertEquals(offset == 0L ? -1L : offset, capturedOffset.get());
            assertEquals(expected, actual);
        }
    }

    private static final class TestHarnessServlet extends RestApiServlet
    {
        TestHarnessServlet(ScheduledExecutorService executor)
        {
            super(
                new RestApiService() {
                    @Override
                    public String getPublicEndpointUrl()
                    {
                        return "http://localhost/sensorhub/api";
                    }

                    @Override
                    public ScheduledExecutorService getThreadPool()
                    {
                        return executor;
                    }
                },
                (RestApiSecurity) Proxy.newProxyInstance(
                    RestApiSecurity.class.getClassLoader(),
                    new Class<?>[] { RestApiSecurity.class },
                    (proxy, method, args) -> null),
                null,
                LoggerFactory.getLogger(TestHarnessServlet.class));
        }

        @Override
        protected RequestContext createContext(HttpServletRequest req, HttpServletResponse resp)
        {
            return new RequestContext(this, req, resp);
        }

        void invokeDispatch(HttpServletRequest req, HttpServletResponse resp, AsyncRequestAction action)
        {
            dispatchAsync(req, resp, action);
        }
    }

    private static final class MockAsyncHandle
    {
        final AtomicReference<AsyncListener> listenerRef = new AtomicReference<>();
        final AtomicInteger status = new AtomicInteger(HttpServletResponse.SC_OK);
        final AtomicInteger completeCalls = new AtomicInteger();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final AsyncContext asyncContext;
        final HttpServletRequest request;
        final HttpServletResponse response;

        MockAsyncHandle(boolean throwOnSecondComplete)
        {
            this.response = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] { HttpServletResponse.class },
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "setStatus":
                            status.set((Integer) args[0]);
                            return null;
                        case "isCommitted":
                            return false;
                        case "getOutputStream":
                            return new ServletOutputStream() {
                                @Override
                                public boolean isReady()
                                {
                                    return true;
                                }

                                @Override
                                public void setWriteListener(WriteListener writeListener)
                                {
                                }

                                @Override
                                public void write(int b)
                                {
                                    body.write(b);
                                }
                            };
                        default:
                            return defaultReturn(method.getReturnType());
                    }
                });

            AtomicReference<HttpServletRequest> reqHolder = new AtomicReference<>();
            this.asyncContext = (AsyncContext) Proxy.newProxyInstance(
                AsyncContext.class.getClassLoader(),
                new Class<?>[] { AsyncContext.class },
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "addListener":
                            listenerRef.set((AsyncListener) args[0]);
                            return null;
                        case "complete":
                            int count = completeCalls.incrementAndGet();
                            if (throwOnSecondComplete && count >= 1)
                                throw new IllegalStateException("AsyncContext already recycled");
                            return null;
                        case "getRequest":
                            return reqHolder.get();
                        case "getResponse":
                            return response;
                        default:
                            return defaultReturn(method.getReturnType());
                    }
                });

            this.request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class },
                (proxy, method, args) -> {
                    switch (method.getName())
                    {
                        case "startAsync":
                            return asyncContext;
                        case "getMethod":
                            return "GET";
                        case "getRequestURI":
                            return "/sensorhub/api/observations";
                        case "getPathInfo":
                            return "/observations";
                        case "getParameterMap":
                            return Collections.emptyMap();
                        case "getHeader":
                            return "Accept".equals(args[0]) ? "application/om+json" : null;
                        case "getRemoteAddr":
                            return "127.0.0.1";
                        default:
                            return defaultReturn(method.getReturnType());
                    }
                });
            reqHolder.set(this.request);
        }

        void fireError(Throwable error) throws IOException
        {
            AsyncListener listener = listenerRef.get();
            if (listener != null)
                listener.onError(new AsyncEvent(asyncContext, request, response, error));
        }

        void fireTimeout() throws IOException
        {
            AsyncListener listener = listenerRef.get();
            if (listener != null)
                listener.onTimeout(new AsyncEvent(asyncContext, request, response));
        }

        private static Object defaultReturn(Class<?> returnType)
        {
            if (returnType == boolean.class)
                return false;
            if (returnType == int.class)
                return 0;
            if (returnType == long.class)
                return 0L;
            return null;
        }
    }
}
