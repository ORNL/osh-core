/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Oak Ridge National Laboratory. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore.obs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import org.geotools.api.filter.And;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.Not;
import org.geotools.api.filter.PropertyIsEqualTo;
import org.geotools.api.filter.PropertyIsNotEqualTo;
import org.geotools.api.filter.expression.Literal;
import org.geotools.api.filter.expression.PropertyName;
import org.geotools.filter.text.cql2.CQLException;
import org.junit.Test;


public class TestObsFilterCql2
{
    @Test
    public void testNotEqualsOperatorMatchesAngleBracketForm() throws Exception
    {
        Filter bangEquals = ObsFilter.parseCqlFilter("adjudicatedIds!='eml-non-threat'");
        Filter angleBrackets = ObsFilter.parseCqlFilter("adjudicatedIds<>'eml-non-threat'");

        assertTrue(bangEquals instanceof Not || bangEquals instanceof PropertyIsNotEqualTo);
        assertEquals(angleBrackets, bangEquals);

        ObsFilter obsFilter = new ObsFilter.Builder()
            .withCQLFilter("adjudicatedIds != 'eml-non-threat'")
            .build();
        assertEquals(angleBrackets, obsFilter.getCQLFilter());
    }


    @Test
    public void testQuotedLiteralsContainingNotEqualsRemainUntouched() throws Exception
    {
        Filter filter = ObsFilter.parseCqlFilter("note='a!=b' AND comment='it''s != literal' AND status!='Fault'");
        List<Filter> leaves = new ArrayList<>();
        collectConjuncts(filter, leaves);
        assertEquals(3, leaves.size());

        PropertyIsEqualTo noteEq = (PropertyIsEqualTo)leaves.get(0);
        assertEquals("note", ((PropertyName)noteEq.getExpression1()).getPropertyName());
        assertEquals("a!=b", ((Literal)noteEq.getExpression2()).getValue());

        PropertyIsEqualTo commentEq = (PropertyIsEqualTo)leaves.get(1);
        assertEquals("comment", ((PropertyName)commentEq.getExpression1()).getPropertyName());
        assertEquals("it's != literal", ((Literal)commentEq.getExpression2()).getValue());

        Filter third = leaves.get(2);
        if (third instanceof Not)
        {
            PropertyIsEqualTo inner = (PropertyIsEqualTo)((Not)third).getFilter();
            assertEquals("status", ((PropertyName)inner.getExpression1()).getPropertyName());
            assertEquals("Fault", ((Literal)inner.getExpression2()).getValue());
        }
        else
        {
            PropertyIsNotEqualTo statusNe = (PropertyIsNotEqualTo)third;
            assertEquals("status", ((PropertyName)statusNe.getExpression1()).getPropertyName());
            assertEquals("Fault", ((Literal)statusNe.getExpression2()).getValue());
        }
    }


    private static void collectConjuncts(Filter filter, List<Filter> out)
    {
        if (filter instanceof And)
        {
            for (Filter child : ((And)filter).getChildren())
                collectConjuncts(child, out);
        }
        else
        {
            out.add(filter);
        }
    }


    @Test
    public void testMalformedFiltersThrowCqlException()
    {
        for (String invalid : new String[] {"", "   ", "adjudicatedIds ! 'eml-non-threat'", "status = 'unterminated"})
        {
            try
            {
                ObsFilter.parseCqlFilter(invalid);
                fail("Expected CQLException for invalid CQL2 predicate: " + invalid);
            }
            catch (CQLException expected)
            {
                // expected
            }
        }
    }
}
