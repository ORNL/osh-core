/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.sensorhub.api.security.IPermission;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.impl.service.consys.resource.IResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.stream.WebSocketOut;
import org.slf4j.Logger;
import com.google.gson.stream.JsonWriter;


@SuppressWarnings("serial")
public abstract class RestApiServlet extends HttpServlet
{
    static final String LOG_REQUEST_MSG = "{} {}{} (from ip={}, user={})";
    static final String INTERNAL_ERROR_MSG = "Internal server error";
    static final String REQUEST_TIMEOUT_MSG = "Request timed out";
    static final long ASYNC_REQUEST_TIMEOUT_MS = 60_000L;
    static final String INTERNAL_ERROR_LOG_MSG = INTERNAL_ERROR_MSG + " while processing request " + LOG_REQUEST_MSG;
    static final String ACCESS_DENIED_ERROR_MSG = "Permission denied";
    static final String JSON_CONTENT_TYPE = "application/json";
    static final String DEFAULT_CONTENT_SECURITY_POLICY =
        "default-src 'self'; " +
        "script-src 'self' 'unsafe-inline' https:; " +
        "style-src 'self' 'unsafe-inline' https:; " +
        "img-src 'self' data: https:; " +
        "connect-src 'self' https:; " +
        "font-src 'self'; " +
        "object-src 'none'; " +
        "base-uri 'none'; " +
        "frame-ancestors 'none'";

    protected final RestApiSecurity securityHandler;
    protected final IResourceHandler rootHandler;
    protected final ScheduledExecutorService threadPool;
    protected final Logger log;
    protected final String rootUrl;
    protected final boolean allowHtmlResponses;
    protected WebSocketServletFactory wsFactory;
    
    
    public static class ResourcePermissions
    {
        public IPermission get;
        public IPermission list;
        public IPermission stream;
        public IPermission create;
        public IPermission update;
        public IPermission delete;
        public IPermission allOps;
        
        public ResourcePermissions() {}
    }
    

    protected RestApiServlet(RestApiService service, RestApiSecurity securityHandler, IResourceHandler rootHandler, Logger logger)
    {
        this.threadPool = service.getThreadPool();
        this.securityHandler = securityHandler;
        this.rootHandler = rootHandler;
        this.log = logger;
        this.allowHtmlResponses = !(service instanceof ConSysApiService) ||
            ((ConSysApiService)service).getConfiguration().allowHtmlResponses;
        
        var endPointUrl = service.getPublicEndpointUrl();
        this.rootUrl = endPointUrl.endsWith("/") ? endPointUrl.substring(0, endPointUrl.length()-1) : endPointUrl;
    }


    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);

        // create websocket factory
        try
        {
            WebSocketPolicy wsPolicy = new WebSocketPolicy(WebSocketBehavior.SERVER);
            wsFactory = new WebSocketServerFactory(getServletContext(), wsPolicy);
            wsFactory.start();
        }
        catch (Exception e)
        {
            throw new ServletException("Cannot initialize websocket factory", e);
        }
    }


    @Override
    public void destroy()
    {
        // destroy websocket factory
        try
        {
            if (wsFactory != null)
                wsFactory.stop();
        }
        catch (Exception e)
        {
            log.error("Cannot stop websocket factory", e);
        }
    }
    
    
    protected void setCurrentUser(HttpServletRequest req)
    {
        String userID = ISecurityManager.ANONYMOUS_USER;
        if (req.getRemoteUser() != null)
            userID = req.getRemoteUser();
        securityHandler.setCurrentUser(userID);
    }
    
    
    protected void clearCurrentUser()
    {
        securityHandler.clearCurrentUser();
    }
    
    
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        logRequest(req);
        setSecurityHeaders(resp);
        super.service(req, resp);
    }


    protected void setSecurityHeaders(HttpServletResponse resp)
    {
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Content-Security-Policy", DEFAULT_CONTENT_SECURITY_POLICY);
        resp.setHeader("X-Frame-Options", "DENY");
    }


    public boolean allowHtmlResponses()
    {
        return allowHtmlResponses;
    }


    @FunctionalInterface
    protected interface AsyncRequestAction
    {
        void execute(RequestContext ctx) throws Exception;
    }


    private static void cancelQueuedTask(Future<?> task)
    {
        cancelQueuedTask(task, false);
    }


    private static void cancelQueuedTask(Future<?> task, boolean mayInterrupt)
    {
        if (task != null && !task.isDone())
            task.cancel(mayInterrupt);
    }


    private static void completeAsyncQuietly(AsyncContext aCtx)
    {
        if (aCtx == null)
            return;
        try
        {
            aCtx.complete();
        }
        catch (IllegalStateException ignored)
        {
            // AsyncContext already completed or timed out
        }
    }


    static boolean isRequestConnectionClosed(HttpServletRequest req)
    {
        if (req == null)
            return false;
        try
        {
            ServletRequest current = req;
            for (int depth = 0; current instanceof ServletRequestWrapper && depth < 8; depth++)
                current = ((ServletRequestWrapper)current).getRequest();
            if (current == null || !"org.eclipse.jetty.server.Request".equals(current.getClass().getName()))
                return false;

            Method getHttpChannel = current.getClass().getMethod("getHttpChannel");
            Object channel = getHttpChannel.invoke(current);
            if (channel == null)
                return false;

            try
            {
                Method isAborted = channel.getClass().getMethod("isAborted");
                if (Boolean.TRUE.equals(isAborted.invoke(channel)))
                    return true;
            }
            catch (NoSuchMethodException ignored)
            {
            }

            Method getEndPoint = channel.getClass().getMethod("getEndPoint");
            Object endPoint = getEndPoint.invoke(channel);
            if (endPoint == null)
                return true;

            Method isOpen = endPoint.getClass().getMethod("isOpen");
            if (!Boolean.TRUE.equals(isOpen.invoke(endPoint)))
                return true;

            Method isOutputShutdown = endPoint.getClass().getMethod("isOutputShutdown");
            return Boolean.TRUE.equals(isOutputShutdown.invoke(endPoint));
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }


    protected void dispatchAsync(HttpServletRequest req, HttpServletResponse resp, AsyncRequestAction action)
    {
        try
        {
            resp.setContentType(JSON_CONTENT_TYPE);
            var ctx = createContext(req, resp);

            final boolean isReadOnlyRequest = "GET".equalsIgnoreCase(req.getMethod())
                    || "HEAD".equalsIgnoreCase(req.getMethod());
            final AsyncContext aCtx = req.startAsync(req, resp);
            aCtx.setTimeout(ASYNC_REQUEST_TIMEOUT_MS);

            final AtomicBoolean asyncSettled = new AtomicBoolean(false);
            final AtomicReference<Future<?>> taskRef = new AtomicReference<>();

            aCtx.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event)
                {
                    asyncSettled.set(true);
                    cancelQueuedTask(taskRef.get(), false);
                }

                @Override
                public void onTimeout(AsyncEvent event)
                {
                    if (asyncSettled.compareAndSet(false, true))
                    {
                        cancelQueuedTask(taskRef.get(), isReadOnlyRequest);
                        String query = req.getQueryString() != null ? "?" + req.getQueryString() : "";
                        log.warn("Async API request timed out after {} ms: {} {}{}",
                            ASYNC_REQUEST_TIMEOUT_MS, req.getMethod(), req.getRequestURI(), query);
                        sendError(SC_SERVICE_UNAVAILABLE, REQUEST_TIMEOUT_MSG, req, resp);
                        completeAsyncQuietly(event.getAsyncContext());
                    }
                }

                @Override
                public void onError(AsyncEvent event)
                {
                    if (asyncSettled.compareAndSet(false, true))
                    {
                        cancelQueuedTask(taskRef.get(), isReadOnlyRequest);
                        Throwable err = event.getThrowable();
                        if (err != null && isClientDisconnect(err))
                            logClientDisconnect(req, err);
                        completeAsyncQuietly(event.getAsyncContext());
                    }
                }

                @Override
                public void onStartAsync(AsyncEvent event)
                {
                }
            });

            Future<?> submitted = threadPool.submit(() -> {
                if (asyncSettled.get() || (!ctx.isStreamRequest() && isRequestConnectionClosed(req)))
                {
                    if (asyncSettled.compareAndSet(false, true))
                        completeAsyncQuietly(aCtx);
                    return;
                }
                try
                {
                    setCurrentUser(req);
                    action.execute(ctx);
                }
                catch (InvalidRequestException e)
                {
                    if (!asyncSettled.get())
                        handleInvalidRequestException(req, resp, e);
                }
                catch (SecurityException e)
                {
                    if (!asyncSettled.get())
                        handleAuthException(req, resp, e);
                }
                catch (Exception e)
                {
                    if (e.getCause() instanceof InvalidRequestException)
                    {
                        if (!asyncSettled.get())
                            handleInvalidRequestException(req, resp, (InvalidRequestException)e.getCause());
                    }
                    else if (isClientDisconnect(e) || asyncSettled.get() || (!ctx.isStreamRequest() && isRequestConnectionClosed(req)))
                    {
                        logClientDisconnect(req, e);
                    }
                    else
                    {
                        logError(req, e);
                        sendError(SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MSG, req, resp);
                    }
                }
                finally
                {
                    Thread.interrupted();
                    clearCurrentUser();
                    if (asyncSettled.compareAndSet(false, true))
                        completeAsyncQuietly(aCtx);
                }
            });
            taskRef.set(submitted);
            if (asyncSettled.get())
                cancelQueuedTask(submitted, isReadOnlyRequest);
        }
        catch (Exception e)
        {
            if (isClientDisconnect(e))
            {
                logClientDisconnect(req, e);
            }
            else
            {
                logError(req, e);
                sendError(SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MSG, req, resp);
            }
        }
    }


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        dispatchAsync(req, resp, ctx -> rootHandler.doGet(ctx));
    }


    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        dispatchAsync(req, resp, ctx -> {
            rootHandler.doPost(ctx);

            var uriList = ctx.getResourceUris();
            if (uriList.size() == 1)
            {
                resp.setStatus(SC_CREATED);
                resp.setHeader("Location", uriList.iterator().next());
            }
            else if (!uriList.isEmpty())
            {
                resp.setStatus(SC_CREATED);

                // prepare to write JSON response
                try (var writer = new JsonWriter(new OutputStreamWriter(resp.getOutputStream())))
                {
                    writer.beginArray();
                    for (var uri: uriList)
                        writer.value(uri);
                    writer.endArray();
                }
            }
        });
    }


    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        dispatchAsync(req, resp, ctx -> {
            rootHandler.doPut(ctx);
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        });
    }


    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        dispatchAsync(req, resp, ctx -> {
            rootHandler.doDelete(ctx);
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        });
    }
    
    
    protected RequestContext createContext(HttpServletRequest req, HttpServletResponse resp) throws IOException
    {
        // check if we have an upgrade request for websockets
        if (wsFactory.isUpgradeRequest(req, resp))
        {
            /*if (req.getSubProtocols().contains("ingest"))
            {
                // get binding for parsing incoming obs records
                var binding = getBinding(ctx, true);
                return null;
            }
            else
            }*/
            var ws = new WebSocketOut(getLogger());
            
            wsFactory.acceptWebSocket(new WebSocketCreator() {
                @Override
                public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
                {
                    return ws;
                }
            }, req, resp);
            
            return new RequestContext(this, req, resp, ws);
        }
        
        // else just handle as regular HTTP request
        else
            return new RequestContext(this, req, resp);
    }
    
    
    protected void logRequest(HttpServletRequest req)
    {
        if (log.isInfoEnabled())
            logRequestInfo(req, null);
    }
    
    
    static boolean isClientDisconnect(Throwable error)
    {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++)
        {
            String className = current.getClass().getName();
            if (current instanceof EOFException ||
                current instanceof ClosedChannelException ||
                "org.eclipse.jetty.io.EofException".equals(className) ||
                className.endsWith(".ClientAbortException"))
            {
                return true;
            }
            if (current instanceof IOException)
            {
                String message = current.getMessage();
                if (message != null)
                {
                    String lower = message.toLowerCase(Locale.ROOT);
                    if (lower.contains("broken pipe") ||
                        lower.contains("connection reset") ||
                        lower.contains("forcibly closed") ||
                        lower.contains("stream closed") ||
                        lower.contains("closed channel"))
                    {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }


    protected void logClientDisconnect(HttpServletRequest req, Throwable e)
    {
        if (log.isDebugEnabled())
        {
            String method = req.getMethod();
            String url = req.getRequestURI();
            String query = req.getQueryString() != null ? "?" + req.getQueryString() : "";
            log.debug("Client disconnected while processing {} {}{}: {}", method, url, query, e.toString());
        }
    }


    protected void logError(HttpServletRequest req, Throwable e)
    {
        if (isClientDisconnect(e))
            logClientDisconnect(req, e);
        else if (log.isErrorEnabled())
            logRequestInfo(req, e);
    }
    
    
    protected void logRequestInfo(HttpServletRequest req, Throwable error)
    {
        String method = req.getMethod();
        String url = req.getRequestURI();
        String ip = req.getRemoteAddr();
        String user = req.getRemoteUser() != null ? req.getRemoteUser() : "anonymous";
        
        // if proxy header present, use source ip instead of proxy ip
        String proxyHeader = req.getHeader("X-Forwarded-For");
        if (proxyHeader != null)
        {
            String[] ips = proxyHeader.split(",");
            if (ips.length >= 1)
                ip = ips[0];
        }
        
        // detect websocket upgrade
        if ("websocket".equalsIgnoreCase(req.getHeader("Upgrade")))
            method += "/Websocket";
        
        // append decoded request if any
        String query = "";
        if (req.getQueryString() != null)
            query = "?" + req.getQueryString();
        
        if (error != null)
            log.error(INTERNAL_ERROR_LOG_MSG, method, url, query, ip, user, error);
        else
            log.info(LOG_REQUEST_MSG, method, url, query, ip, user);
    }
    
    
    /*public void sendError(int code, String mimeType, HttpServletResponse resp)
    {
        sendError(code, null, mimeType, resp);
    }*/
    
    
    public void sendError(int code, String msg, HttpServletRequest req, HttpServletResponse resp)
    {
        if (resp == null || resp.isCommitted() || isRequestConnectionClosed(req))
            return;

        try
        {
            var accept = req != null ? req.getHeader("Accept") : null;
            
            if (accept == null || accept.contains("json"))
            {
                resp.setStatus(code);
                if (msg != null)
                {
                    var json =
                        "{\n" +
                        "  \"status\": " + code + ",\n" +
                        "  \"message\": \"" + msg.replace("\"", "\\\"") + "\"\n" +
                        "}";
                    resp.getOutputStream().write(json.getBytes());
                }
            }
            else
                resp.sendError(code, msg);
        }
        catch (IOException | IllegalStateException e)
        {
            if (isClientDisconnect(e) || e instanceof IllegalStateException)
                logClientDisconnect(req, e);
            else
                log.error("Could not send error response", e);
        }
    }
    
    
    protected void handleInvalidRequestException(HttpServletRequest req, HttpServletResponse resp, InvalidRequestException e)
    {
        log.debug("Invalid request ({}): {}", e.getErrorCode(), e.getMessage());
        
        switch (e.getErrorCode())
        {
            case UNSUPPORTED_OPERATION:
                sendError(SC_METHOD_NOT_ALLOWED, e.getMessage(), req, resp);
                break;
                
            case BAD_REQUEST:
            case BAD_PAYLOAD:
            case REQUEST_REJECTED:
                sendError(SC_BAD_REQUEST, e.getMessage(), req, resp);
                break;
                
            case NOT_FOUND:
                sendError(SC_NOT_FOUND, e.getMessage(), req, resp);
                break;
                
            case FORBIDDEN:
                sendError(SC_FORBIDDEN, e.getMessage(), req, resp);
                break;
                
            case REQUEST_ACCEPTED_TIMEOUT:
                sendError(202, e.getMessage(), req, resp);
                break;
                
            default:
                sendError(SC_INTERNAL_SERVER_ERROR, e.getMessage(), req, resp);
        }
    }
    
    
    protected void handleAuthException(HttpServletRequest req, HttpServletResponse resp, SecurityException e)
    {
        if (req == null || resp == null || resp.isCommitted() || isRequestConnectionClosed(req))
            return;
        try
        {
            log.debug("Not authorized: {}", e.getMessage());

            if (req.getRemoteUser() == null)
                req.authenticate(resp);
            else
                sendError(SC_FORBIDDEN, ACCESS_DENIED_ERROR_MSG, req, resp);
        }
        catch (IllegalStateException e1)
        {
            logClientDisconnect(req, e1);
        }
        catch (Exception e1)
        {
            if (isClientDisconnect(e1))
                logClientDisconnect(req, e1);
            else
                getLogger().error("Could not send authentication request", e1);
        }
    }
    
    
    public Logger getLogger()
    {
        return this.log;
    }


    public String getApiRootURL(HttpServletRequest req)
    {
        if (rootUrl.contains("localhost") && req != null)
            return rootUrl.replace("localhost", req.getServerName());
        
        return rootUrl;
    }
    
    
    public IResourceHandler getRootHandler()
    {
        return rootHandler;
    }


    public RestApiSecurity getSecurityHandler()
    {
        return securityHandler;
    }
    
}
