/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.sensorhub.api.module.ModuleConfig;


public class TestConfigCloner
{
    static class NestedItem
    {
        public String label;

        NestedItem(String label)
        {
            this.label = label;
        }
    }


    static class SampleConfig extends ModuleConfig
    {
        public List<NestedItem> list0 = List.of();
        public List<NestedItem> list1 = List.of(new NestedItem("one"));
        public List<NestedItem> list2 = List.of(new NestedItem("a"), new NestedItem("b"));
        public List<NestedItem> list3 = List.of(new NestedItem("x"), new NestedItem("y"), new NestedItem("z"));
        public List<String> singletonList = Collections.singletonList("solo");
        public List<String> emptyList = Collections.emptyList();
        public Set<String> set0 = Set.of();
        public Set<String> set1 = Set.of("s1");
        public Set<String> set3 = Set.of("s1", "s2", "s3");
        public Map<String, NestedItem> map0 = Map.of();
        public Map<String, NestedItem> map1 = Map.of("k1", new NestedItem("v1"));
        public Map<String, NestedItem> map2 = Map.of("k1", new NestedItem("v1"), "k2", new NestedItem("v2"));
        public Map<String, String> singletonMap = Collections.singletonMap("sk", "sv");
    }


    @Test
    public void deepClonesJdkImmutableCollectionsWithoutReflectionError()
    {
        SampleConfig orig = new SampleConfig();
        orig.name = "PatrolTest";

        SampleConfig cloned = (SampleConfig)orig.clone();

        assertNotSame(orig, cloned);
        assertEquals("PatrolTest", cloned.name);
        assertEquals(0, cloned.list0.size());
        assertEquals(1, cloned.list1.size());
        assertNotSame(orig.list1.get(0), cloned.list1.get(0));
        assertEquals("one", cloned.list1.get(0).label);
        assertEquals(2, cloned.list2.size());
        assertEquals(3, cloned.list3.size());
        assertEquals(List.of("solo"), cloned.singletonList);
        assertTrue(cloned.emptyList.isEmpty());
        assertTrue(cloned.set0.isEmpty());
        assertEquals(Set.of("s1"), cloned.set1);
        assertEquals(Set.of("s1", "s2", "s3"), cloned.set3);
        assertTrue(cloned.map0.isEmpty());
        assertNotSame(orig.map1.get("k1"), cloned.map1.get("k1"));
        assertEquals("v1", cloned.map1.get("k1").label);
        assertEquals("v2", cloned.map2.get("k2").label);
        assertEquals("sv", cloned.singletonMap.get("sk"));

        // Ensure cloned collections are mutable for Vaadin form editing
        cloned.list1.add(new NestedItem("two"));
        assertEquals(2, cloned.list1.size());
    }
}
