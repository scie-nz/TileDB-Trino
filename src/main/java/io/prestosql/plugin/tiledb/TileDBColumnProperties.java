/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.tiledb;

import com.google.common.collect.ImmutableList;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.spi.type.TypeManager;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;

import static io.prestosql.spi.session.PropertyMetadata.booleanProperty;
import static io.prestosql.spi.session.PropertyMetadata.longProperty;
import static io.prestosql.spi.session.PropertyMetadata.stringProperty;

public class TileDBColumnProperties
{
    public static final String Dimension = "dimension";
    public static final String Nullable = "nullable";
    public static final String LowerBound = "lower_bound";
    public static final String UpperBound = "upper_bound";
    public static final String Extent = "extent";
    public static final String FilterList = "filter_list";
    private final List<PropertyMetadata<?>> columnProperties;

    @Inject
    public TileDBColumnProperties(TypeManager typeManager, TileDBConfig config)
    {
        columnProperties = ImmutableList.of(
                booleanProperty(
                        Dimension,
                        "Is column a dimension?",
                        false,
                        false),
                booleanProperty(
                        Nullable,
                        "Is column nullable?",
                        false,
                        false),
                longProperty(
                        LowerBound,
                        "Domain Lower Bound",
                        Long.MIN_VALUE,
                        false),
                longProperty(
                        UpperBound,
                        "Domain Upper Bound",
                        Long.MAX_VALUE,
                        false),
                longProperty(
                        Extent,
                        "Dimension Extent",
                        10L,
                        false),
                stringProperty(FilterList,
                        "The filter list",
                        "",
                        false));
    }

    public List<PropertyMetadata<?>> getColumnProperties()
    {
        return columnProperties;
    }

    public static Boolean getDimension(Map<String, Object> tableProperties)
    {
        return (Boolean) tableProperties.get(Dimension);
    }

    public static Long getLowerBound(Map<String, Object> tableProperties)
    {
        return (Long) tableProperties.get(LowerBound);
    }

    public static boolean getNullable(Map<String, Object> tableProperties)
    {
        return (boolean) tableProperties.get(Nullable);
    }

    public static Long getUpperBound(Map<String, Object> tableProperties)
    {
        return (Long) tableProperties.get(UpperBound);
    }

    public static String getFilterList(Map<String, Object> tableProperties)
    {
        if (tableProperties.containsKey(FilterList)) {
            return (String) tableProperties.get(FilterList);
        }
        else {
            return "";
        }
    }

    public static Long getExtent(Map<String, Object> tableProperties)
    {
        return (Long) tableProperties.get(Extent);
    }
}
