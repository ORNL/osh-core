/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.sensorhub.impl.security.PermissionSetting;
import com.rits.cloning.Cloner;
import com.rits.cloning.FastClonerCustomCollection;
import com.rits.cloning.IDeepCloner;
import com.rits.cloning.IFastCloner;


public class ConfigCloner extends Cloner
{
    static class FastClonerDate implements IFastCloner
    {
        @Override
        public Object clone(Object t, IDeepCloner cloner, Map<Object, Object> clones)
        {
            return ((Date)t).clone();
        }        
    }
    
    
    static class FastClonerEnumSet implements IFastCloner
    {
        @Override
        public Object clone(Object t, IDeepCloner cloner, Map<Object, Object> clones)
        {
            return ((EnumSet<?>)t).clone();
        }        
    }
    
    
    static class FastClonerPermissionSetting extends FastClonerCustomCollection<PermissionSetting>
    {
        @Override
        public PermissionSetting getInstance(PermissionSetting o)
        {
            return new PermissionSetting();
        }        
    }


    static class FastClonerImmutableList implements IFastCloner
    {
        @Override
        public Object clone(Object t, IDeepCloner cloner, Map<Object, Object> clones)
        {
            Collection<?> src = (Collection<?>)t;
            ArrayList<Object> copy = new ArrayList<>(src.size());
            for (Object item : src)
                copy.add(cloner.deepClone(item, clones));
            return copy;
        }
    }


    static class FastClonerImmutableSet implements IFastCloner
    {
        @Override
        public Object clone(Object t, IDeepCloner cloner, Map<Object, Object> clones)
        {
            Collection<?> src = (Collection<?>)t;
            LinkedHashSet<Object> copy = new LinkedHashSet<>(Math.max(16, src.size() * 2));
            for (Object item : src)
                copy.add(cloner.deepClone(item, clones));
            return copy;
        }
    }


    static class FastClonerImmutableMap implements IFastCloner
    {
        @Override
        public Object clone(Object t, IDeepCloner cloner, Map<Object, Object> clones)
        {
            Map<?, ?> src = (Map<?, ?>)t;
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<>(Math.max(16, src.size() * 2));
            for (Map.Entry<?, ?> entry : src.entrySet())
                copy.put(cloner.deepClone(entry.getKey(), clones), cloner.deepClone(entry.getValue(), clones));
            return copy;
        }
    }
    
    
    public ConfigCloner()
    {
        super();
        
        try {
            this.registerFastCloner(Date.class, new FastClonerDate());
            this.registerFastCloner(Class.forName("java.util.RegularEnumSet"), new FastClonerEnumSet());
            this.registerFastCloner(PermissionSetting.class, new FastClonerPermissionSetting());

            // Java 9+ unmodifiable collection implementations (ImmutableCollections$List12/ListN/Set12/SetN/Map1/MapN)
            // and Collections singleton/empty wrappers cannot be reflectively cloned on JDK 17/21 without --add-opens.
            Set<Class<?>> registered = new LinkedHashSet<>();
            FastClonerImmutableList listCloner = new FastClonerImmutableList();
            registerFastClonerIfAbsent(registered, List.of().getClass(), listCloner);
            registerFastClonerIfAbsent(registered, List.of("a").getClass(), listCloner);
            registerFastClonerIfAbsent(registered, List.of("a", "b", "c").getClass(), listCloner);
            registerFastClonerIfAbsent(registered, Collections.emptyList().getClass(), listCloner);
            registerFastClonerIfAbsent(registered, Collections.singletonList("a").getClass(), listCloner);

            FastClonerImmutableSet setCloner = new FastClonerImmutableSet();
            registerFastClonerIfAbsent(registered, Set.of().getClass(), setCloner);
            registerFastClonerIfAbsent(registered, Set.of("a").getClass(), setCloner);
            registerFastClonerIfAbsent(registered, Set.of("a", "b", "c").getClass(), setCloner);
            registerFastClonerIfAbsent(registered, Collections.emptySet().getClass(), setCloner);
            registerFastClonerIfAbsent(registered, Collections.singleton("a").getClass(), setCloner);

            FastClonerImmutableMap mapCloner = new FastClonerImmutableMap();
            registerFastClonerIfAbsent(registered, Map.of().getClass(), mapCloner);
            registerFastClonerIfAbsent(registered, Map.of("k", "v").getClass(), mapCloner);
            registerFastClonerIfAbsent(registered, Map.of("k1", "v1", "k2", "v2").getClass(), mapCloner);
            registerFastClonerIfAbsent(registered, Collections.emptyMap().getClass(), mapCloner);
            registerFastClonerIfAbsent(registered, Collections.singletonMap("k", "v").getClass(), mapCloner);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    private void registerFastClonerIfAbsent(Set<Class<?>> registered, Class<?> clazz, IFastCloner fastCloner)
    {
        if (registered.add(clazz))
        {
            try
            {
                this.registerFastCloner(clazz, fastCloner);
            }
            catch (IllegalArgumentException ignored)
            {
                // Already registered by Cloner superclass
            }
        }
    }
    
    
    @Override
    public <T> T deepClone(T o)
    {
        return super.deepClone(o);
    }

}
