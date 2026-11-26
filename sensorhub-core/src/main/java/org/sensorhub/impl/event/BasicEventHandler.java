/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.event;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventHandler;
import org.sensorhub.api.event.IEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * <p>
 * Basic implementation of a synchronous event manager.
 * </p><p>
 * We have to take care of cases of calls to register/unregister initiated
 * synchronously (in same thread) by listeners while an event is being dispatched.
 * This is done by using temporary lists and committing changes that occured during
 * the iteration only at the end of the call to publish().
 * </p><p>
 * Likewise recursive calls to publish have to be handled as well. This is done 
 * by accumulating events in a queue and dispatching each one in their own loop,
 * thus avoiding recursive calls to publish while iterating.
 * </p><p>
 * We use weak references in the main list of listeners to prevent memory leaks in
 * cases where listeners forget to unregister themselves.
 * </p>
 *
 * @author Alex Robin
 * @since Nov 16, 2010
 */
public class BasicEventHandler implements IEventHandler
{
    static final Logger log = LoggerFactory.getLogger(BasicEventHandler.class);
    
    List<IEventListener> listeners = new ArrayList<>();
    Deque<Event> eventQueue = new LinkedBlockingDeque<>();
    boolean inPublish = false;
    
    
    @Override
    public synchronized void registerListener(IEventListener listener)
    {
        if (!listeners.contains(listener))
            listeners.add(listener);
    }


    @Override
    public synchronized void unregisterListener(IEventListener listener)
    {
        listeners.remove(listener);
    }
    
    
    @Override
    public void publish(Event e)
    {
        List<IEventListener> snapshot;
        synchronized (this)
        {
            // Enqueue recursive or concurrent publishes so listener callbacks execute
            // outside this monitor and cannot invert lock order with module/UI locks.
            if (inPublish)
            {
                eventQueue.addLast(e);
                return;
            }
            inPublish = true;
            snapshot = new ArrayList<>(listeners);
        }

        Event current = e;
        try
        {
            while (true)
            {
                dispatchSnapshot(current, snapshot);
                synchronized (this)
                {
                    if (eventQueue.isEmpty())
                    {
                        inPublish = false;
                        return;
                    }
                    current = eventQueue.pollFirst();
                    snapshot = new ArrayList<>(listeners);
                }
            }
        }
        finally
        {
            synchronized (this)
            {
                if (inPublish)
                {
                    eventQueue.clear();
                    inPublish = false;
                }
            }
        }
    }


    private void dispatchSnapshot(Event e, List<IEventListener> snapshot)
    {
        for (IEventListener listener : snapshot)
        {
            try
            {
                listener.handleEvent(e);
            }
            catch (Throwable ex)
            {
                String srcName = e != null && e.getSource() != null ? e.getSource().getClass().getSimpleName()
                        : (e != null ? e.getSourceID() : "unknown");
                String destName = listener.getClass().getSimpleName();
                if (destName.isEmpty()) // case of anonymous class
                {
                    destName = listener.getClass().getName();
                    destName = destName.substring(destName.lastIndexOf('.')+1);
                }
                log.error("Uncaught exception while dispatching event from {} to {}", srcName, destName, ex);
            }
        }
    }
    
    
    @Override
    public synchronized int getNumListeners()
    {
        return listeners.size();
    }


    @Override
    public synchronized void clearAllListeners()
    {
        listeners.clear();
    }
}
