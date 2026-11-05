package org.sensorhub.impl.service.consys;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import org.eclipse.jetty.io.EofException;
import org.junit.Test;

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
}
