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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventHandler;
import org.sensorhub.api.event.IEventListener;


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
public class WeakEventHandler implements IEventHandler
{
    List<WeakReference<IEventListener>> listeners = new ArrayList<>();
    Deque<Event> eventQueue = new LinkedBlockingDeque<>();
    boolean inPublish = false;
    
    
    @Override
    public synchronized void registerListener(IEventListener listener)
    {
        if (!hasWeakRef(listener))
            addWeakRef(listener);
    }


    @Override
    public synchronized void unregisterListener(IEventListener listener)
    {
        removeWeakRef(listener);
    }
    
    
    @Override
    public void publish(Event e)
    {
        List<IEventListener> snapshot;
        synchronized (this)
        {
            if (inPublish)
            {
                eventQueue.addLast(e);
                return;
            }
            inPublish = true;
            snapshot = snapshotLiveListeners();
        }

        Event current = e;
        try
        {
            while (true)
            {
                for (IEventListener listener : snapshot)
                    listener.handleEvent(current);

                synchronized (this)
                {
                    if (eventQueue.isEmpty())
                    {
                        inPublish = false;
                        return;
                    }
                    current = eventQueue.pollFirst();
                    snapshot = snapshotLiveListeners();
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


    private List<IEventListener> snapshotLiveListeners()
    {
        List<IEventListener> live = new ArrayList<>(listeners.size());
        for (Iterator<WeakReference<IEventListener>> it = listeners.iterator(); it.hasNext(); )
        {
            IEventListener listener = it.next().get();
            if (listener != null)
                live.add(listener);
            else
                it.remove(); // purge cleared references
        }
        return live;
    }
    
    
    private final void addWeakRef(IEventListener listener)
    {
        listeners.add(new WeakReference<IEventListener>(listener));
    }
    
    
    private final boolean hasWeakRef(IEventListener listenerToFind)
    {
        for (Iterator<WeakReference<IEventListener>> it = listeners.iterator(); it.hasNext(); )
        {
            IEventListener listener = it.next().get();
            if (listener == listenerToFind)
                return true;
        }
        
        return false;
    }
    
    
    private final void removeWeakRef(IEventListener listenerToRemove)
    {
        for (Iterator<WeakReference<IEventListener>> it = listeners.iterator(); it.hasNext(); )
        {
            IEventListener listener = it.next().get();
            if (listener == null || listener == listenerToRemove)  // also purge cleared references
                it.remove();
        }
    }
    
    
    @Override
    public synchronized int getNumListeners()
    {
        int count = 0;
        for (WeakReference<IEventListener> listener: listeners)
        {
            if (listener.get() != null)
                count++;
        }
        return count;
    }


    @Override
    public synchronized void clearAllListeners()
    {
        listeners.clear();
    }
}
