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

import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.plugin.tiledb.util.Util;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.Marker;
import io.prestosql.spi.predicate.Range;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import io.tiledb.java.api.Array;
import io.tiledb.java.api.ArraySchema;
import io.tiledb.java.api.Attribute;
import io.tiledb.java.api.Datatype;
import io.tiledb.java.api.Dimension;
import io.tiledb.java.api.NativeArray;
import io.tiledb.java.api.Pair;
import io.tiledb.java.api.Query;
import io.tiledb.java.api.QueryStatus;
import io.tiledb.java.api.Stats;
import io.tiledb.java.api.TileDBError;
import org.apache.commons.beanutils.ConvertUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.plugin.tiledb.TileDBErrorCode.TILEDB_RECORD_CURSOR_ERROR;
import static io.prestosql.plugin.tiledb.TileDBErrorCode.TILEDB_RECORD_SET_ERROR;
import static io.prestosql.plugin.tiledb.TileDBSessionProperties.getEnableStats;
import static io.prestosql.plugin.tiledb.TileDBSessionProperties.getReadBufferSize;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static io.tiledb.java.api.Datatype.TILEDB_UINT64;
import static io.tiledb.java.api.Datatype.TILEDB_UINT8;
import static io.tiledb.java.api.QueryStatus.TILEDB_COMPLETED;
import static io.tiledb.java.api.QueryStatus.TILEDB_INCOMPLETE;
import static io.tiledb.java.api.QueryStatus.TILEDB_UNINITIALIZED;
import static io.tiledb.java.api.Types.getJavaType;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.max;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * TileDBRecordCursor is a typical sql/db interface cursor. It is responsible for iterating over the results, fetching
 * and returning the columns. This implements getBoolean/getLong/getDouble/getSlice for returning from tiledb storage
 * types to these 4 supported types in prestodb.
 * <p>
 * The cursor also issues the query to tiledb, currently reading the entire array. Results are poorly iterated over by
 * just going through the result buffers in a serial fashion. This is to be optimized.
 */
public class TileDBRecordCursor
        implements RecordCursor
{
    private static final Logger LOG = Logger.get(TileDBRecordCursor.class);

    /**
     * If true, record and report function timings.
     */
    private final boolean functionTimingEnabled;

    /**
     * Max read buffer size (in bytes) to allocate initially, per attribute.
     */
    private final int initialMaxBufferSize;

    private final TileDBClient tileDBClient;
    private final Query query;
    private final Array array;
    private final List<TileDBColumnHandle> columnHandles;
    private Map<String, Pair<Long, Long>> queryResultBufferElements;
    private Map<String, List<Pair<Long, Long>>> functionTimings;
    private final String queryId;

    /**
     * TileDB Query status
     */
    private QueryStatus queryStatus;

    /**
     * Map of attribute name -> Presto column (field) index.
     */
    private final Map<String, Integer> columnIndexLookup;

    /**
     * Attribute array so we can avoid looking up attributes by string name
     * This is indexed based on columnHandles indexing (aka query field indexes)
     */
    private final Datatype[] fieldTypes;

    /**
     * Attribute array so we can avoid looking up attribute names.
     * This is indexed based on columnHandles indexing (aka query field indexes)
     */
    private final String[] fieldNames;
    /**
     * Attribute array so we can avoid looking up if an attribute is nullable.
     */
    private final boolean[] fieldNullables;
    /**
     * The array schema instance. No need to close()
     */
    private final ArraySchema arraySchema;

    /**
     * Bytes read and reported to presto
     */
    private long bytesRead;

    /**
     * Start time of querying for data in nanoseconds
     * This is used to report to presto via getReadTimeNanos
     */
    private long nanoStartTime;

    /**
     * End time of querying for data in nanoseconds
     * This is used to report to presto via getReadTimeNanos
     */
    private long nanoEndTime;

    /**
     * NativeArray used for querying. Stored as class variable so it can be free'd.
     */
    private NativeArray subArray;

    /**
     * Map of dimension name -> index of dimension.
     */
    private final Map<String, Integer> dimensionIndexes;

    /**
     * Set to true once close() has been called on the cursor.
     */
    private boolean closed;

    /**
     * The current position of the cursor, relative to the current result set.
     */
    private int cursorPosition = -1;

    /**
     * Upper bound on the total number of resulting records in the select query.
     */
    private long totalNumRecordsUB = Long.MAX_VALUE;

    /**
     * List of NativeArray buffers used in the query object.
     * This is indexed based on columnHandles indexing (aka query field indexes)
     */
    private final ArrayList<Pair<NativeArray, NativeArray>> queryBuffers;

    /**
     * List of validity maps used in the query object.
     * This is indexed based on columnHandles indexing (aka query field indexes)
     */
    private final ArrayList<NativeArray> validityMaps;

    /**
     * List of Java arrays containing query results.
     * This is indexed based on columnHandles indexing (aka query field indexes)
     */
    private final ArrayList<Pair<Object, Object>> queryResultArrays;

    /**
     * Number of records in the current query result set.
     */
    private long currentNumRecords = -2;

    /**
     * Total number of records that have been visited by the cursor.
     */
    private long numRecordsRead;

    /**
     * The zero-epoch OffsetDateTime
     */

    private static final OffsetDateTime zeroDateTime = new Timestamp(0).toInstant().atOffset(ZoneOffset.UTC);

    public TileDBRecordCursor(TileDBClient tileDBClient, ConnectorSession session, TileDBSplit split, List<TileDBColumnHandle> columnHandles, Array array, Query query)
    {
        this.tileDBClient = requireNonNull(tileDBClient, "tileDBClient is null");
        this.columnHandles = columnHandles;
        this.array = array;
        this.query = query;
        int columnCount = max(this.columnHandles.size(), 1);
        this.queryBuffers = new ArrayList<>(Collections.nCopies(columnCount, null));
        this.validityMaps = new ArrayList<>(Collections.nCopies(columnCount, null));
        this.queryResultArrays = new ArrayList<>(Collections.nCopies(columnCount, null));
        this.dimensionIndexes = new HashMap<>();
        this.columnIndexLookup = new HashMap<>();
        this.fieldTypes = new Datatype[columnCount];
        this.fieldNames = new String[columnCount];
        this.fieldNullables = new boolean[columnCount];
        this.queryStatus = TILEDB_UNINITIALIZED;

        // Set initial max buffer sizes for reads from session configuration parameter
        this.initialMaxBufferSize = getReadBufferSize(session);
        this.nanoStartTime = System.nanoTime();
        this.queryId = session.getQueryId();

        try {
            if (getEnableStats(session)) {
                functionTimingEnabled = true;
                this.functionTimings = new HashMap<>();
                Stats.enable();
            }
            else {
                functionTimingEnabled = false;
                Stats.disable();
                Stats.reset();
            }
            this.arraySchema = array.getSchema();
            initializeQuery(split);
        }
        catch (TileDBError tileDBError) {
            throw new PrestoException(TILEDB_RECORD_CURSOR_ERROR, tileDBError);
        }
    }

    /**
     * Initialize the TileDB query for the given split.
     */
    private void initializeQuery(TileDBSplit split) throws TileDBError
    {
        Pair<Long, Long> timer = startTimer();

        for (int i = 0; i < columnHandles.size(); i++) {
            columnIndexLookup.put(columnHandles.get(i).getColumnName(), i);
        }
        HashMap<String, Pair<Integer, Integer>> estimations = new HashMap<>();
        String name;

        // Build attribute array to avoid making calls to ArraySchema.getAttribute(string)
        //Also make result size estimations to allocate the buffers.
        for (int i = 0; i < arraySchema.getAttributeNum(); i++) {
            try (Attribute attribute = arraySchema.getAttribute(i)) {
                name = attribute.getName();
                if (attribute.isVar()) {
                    if (attribute.getNullable()) {
                        estimations.put(name, query.getEstResultSizeVarNullable(tileDBClient.getCtx(), name).getFirst());
                    }
                    else {
                        estimations.put(name, query.getEstResultSizeVar(tileDBClient.getCtx(), name));
                    }
                }
                else {
                    if (attribute.getNullable()) {
                        estimations.put(name, new Pair<>(null, query.getEstResultSizeNullable(tileDBClient.getCtx(), name).getFirst()));
                    }
                    else {
                        estimations.put(name, new Pair<>(null, query.getEstResultSize(tileDBClient.getCtx(), name)));
                    }
                }
                if (columnIndexLookup.containsKey(attribute.getName())) {
                    int field = columnIndexLookup.get(attribute.getName());
                    fieldTypes[field] = attribute.getType();
                    fieldNullables[field] = attribute.getNullable();
                    fieldNames[field] = attribute.getName();
                }
            }
        }

        try (io.tiledb.java.api.Domain domain = arraySchema.getDomain()) {
            // If we're empty let's at least select the first dimension
            // this is needed for count queries
            if (columnIndexLookup.isEmpty()) {
                columnIndexLookup.put(domain.getDimension(0).getName(), 0);
            }

            for (int i = 0; i < domain.getNDim(); i++) {
                try (Dimension dim = domain.getDimension(i)) {
                    name = dim.getName();
                    if (dim.isVar()) {
                        estimations.put(name, query.getEstResultSizeVar(tileDBClient.getCtx(), name));
                    }
                    else {
                        estimations.put(name, new Pair<>(null, query.getEstResultSize(tileDBClient.getCtx(), name)));
                    }
                    if (columnIndexLookup.containsKey(dim.getName())) {
                        int field = columnIndexLookup.get(dim.getName());
                        fieldTypes[field] = dim.getType();
                    }
                }
            }
        }
        // Build ranges
        setRanges(split);
        // Compute an upper bound on the number of results in the subarray.
        totalNumRecordsUB = estimations.values().iterator().next().getSecond();

        // Build buffers for each column (attribute) in the query.
        for (Map.Entry<String, Pair<Integer, Integer>> maxSize : estimations.entrySet()) {
            String columnName = maxSize.getKey();

            // Check to see if column is in request list, if not don't set a buffer
            // Always set dimension buffers though, this is needed for count queries and should be optimized
            if (!columnIndexLookup.containsKey(columnName)) {
                continue;
            }

            // Allocate and set the buffer on the query object.
            initQueryBufferForField(columnName, maxSize.getValue());
        }

        recordFunctionTime("initializeQuery", timer);
    }

    /**
     * Allocates a NativeArray buffer for the given attribute and adds it to the query object.
     */
    private void initQueryBufferForField(String field, Pair<Integer, Integer> maxBufferElements) throws TileDBError
    {
        Pair<Long, Long> timer = startTimer();
        boolean isAttribute = arraySchema.getAttributes().containsKey(field);
        boolean isVar;
        boolean isNullable;
        Datatype type;

        // Get the datatype and if the attribute is variable-sized
        if (!isAttribute) {
            try (io.tiledb.java.api.Domain domain = arraySchema.getDomain(); Dimension dim = domain.getDimension(field)) {
                type = dim.getType();
                isVar = dim.isVar();
                isNullable = false;
            }
        }
        else {
            try (Attribute attr = arraySchema.getAttribute(field)) {
                type = attr.getType();
                isVar = attr.isVar();
                isNullable = attr.getNullable();
            }
        }

        // Allocate a NativeBuffer for the attribute, and the offsets (for var-len attributes).
        int bufferSize = getClampedBufferSize(maxBufferElements.getSecond().intValue(), type.getNativeSize());
        NativeArray valuesBuffer = new NativeArray(tileDBClient.getCtx(), bufferSize, type);
        NativeArray validityMap = new NativeArray(tileDBClient.getCtx(), bufferSize, TILEDB_UINT8);

        if (isVar) {
            // Allocate a buffer for the offsets
            bufferSize = getClampedBufferSize(maxBufferElements.getFirst().intValue(), TILEDB_UINT64.getNativeSize());
            NativeArray offsetsBuffer = new NativeArray(tileDBClient.getCtx(), bufferSize, TILEDB_UINT64);
            queryBuffers.set(columnIndexLookup.get(field), new Pair<>(offsetsBuffer, valuesBuffer));
            if (isNullable) {
                query.setBufferNullable(field, offsetsBuffer, valuesBuffer, validityMap);
                validityMaps.set(columnIndexLookup.get(field), validityMap);
            }
            else {
                query.setBuffer(field, offsetsBuffer, valuesBuffer);
            }
        }
        else {
            if (isNullable) {
                query.setBufferNullable(field, valuesBuffer, validityMap);
                validityMaps.set(columnIndexLookup.get(field), validityMap);
            }
            else {
                query.setBuffer(field, valuesBuffer);
            }
            queryBuffers.set(columnIndexLookup.get(field), new Pair<>(null, valuesBuffer));
        }

        recordFunctionTime("initQueryBufferForAttribute", timer);
    }

    /**
     * Calculate a buffer size, clamped to the maximum size. The returned value is the number of elements for the buffer.
     */
    private int getClampedBufferSize(int numElements, int elementBytes)
    {
        int bufferSize = numElements * elementBytes;
        // Check to see if the buffer size is above the max size
        // or if it is negative (overflow to do java not having unsigned values)
        if (bufferSize > initialMaxBufferSize || bufferSize < 0) {
            return initialMaxBufferSize / elementBytes;
        }
        return bufferSize;
    }

    /**
     * Build the ranges for a query based on the split
     *
     * @param split the split to build the subArray based off of
     * @return
     */
    private void setRanges(TileDBSplit split) throws TileDBError
    {
        Pair<Long, Long> timer = startTimer();
        try (io.tiledb.java.api.Domain domain = arraySchema.getDomain()) {
            List<Dimension> dimensions = domain.getDimensions();
            HashMap<String, Pair> nonEmptyDomain = this.array.nonEmptyDomain();

            int dimIdx = 0;
            for (Dimension dimension : dimensions) {
                Pair dimBounds = getBoundsForDimension(split, dimension, nonEmptyDomain);
                Class classType = getJavaType(dimension.getType());
                dimensionIndexes.put(dimension.getName(), dimIdx);

                if (dimension.isVar()) {
                    if (dimBounds.getFirst().equals(dimBounds.getSecond())) {
                        continue;
                    }

                    query.addRangeVar(dimIdx, dimBounds.getFirst().toString(), dimBounds.getSecond().toString());
                }
                else {
                    query.addRange(dimIdx, ConvertUtils.convert(dimBounds.getFirst(), classType),
                            ConvertUtils.convert(dimBounds.getSecond(), classType));
                }

                LOG.info("Query %s setting range for dimension %s to [%s, %s]", queryId, dimension.getName(), dimBounds.getFirst(), dimBounds.getSecond());
                dimIdx++;
                dimension.close();
            }
        }

        recordFunctionTime("setRanges", timer);
    }

    /**
     * Compute a (lower, upper) bound for the given dimension based on the given split.
     */
    private Pair getBoundsForDimension(TileDBSplit split, Dimension dimension, HashMap<String, Pair> arrayNonEmptyDomain) throws TileDBError
    {
        Pair<Long, Long> timer = startTimer();
        TupleDomain<ColumnHandle> tupleDomain = split.getTupleDomain();
        checkState(tupleDomain.getDomains().isPresent(), "No domains in tuple domain");
        TileDBColumnHandle columnHandle = getColumnHandle(dimension.getName());

        // Get the non-empty domain from the array
        String dimensionName = dimension.getName();
        Pair domain = arrayNonEmptyDomain.get(dimensionName);
        Object nonEmptyMin = domain.getFirst();
        Object nonEmptyMax = domain.getSecond();
        Domain domainPredicate;

        // if the columnHandle is not null use it to lookup the domain
        if (columnHandle != null) {
            domainPredicate = tupleDomain.getDomains().get().get(columnHandle);
        }
        // If the columnHandle is null, it means the dimension is not being selected, we need to loop through all domains to find it
        else {
            domainPredicate = tupleDomain.getDomains().get().entrySet()
                    .stream()
                    .filter(e -> ((TileDBColumnHandle) e.getKey()).getColumnName().equals(dimensionName))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        if (domainPredicate == null || domainPredicate.isAll()) {
            return new Pair<>(nonEmptyMin, nonEmptyMax);
        }

        checkArgument(domainPredicate.getType().isOrderable(), "Domain type must be orderable");
        checkArgument(!domainPredicate.isNone(), "Domain can not specify none, there would be nothing to query!");

        // Perform a union over all ranges to determine an overall lower/upper bound.
        List<Range> orderedRanges = domainPredicate.getValues().getRanges().getOrderedRanges();
        Class dimType = nonEmptyMin.getClass();
        Object dimLowerBound = null;
        Object dimUpperBound = null;
        for (Range range : orderedRanges) {
            Pair bounds = getBoundsForPrestoRange(range, dimension.getType());
            Object rangeLow = bounds.getFirst() == null ? null : ConvertUtils.convert(bounds.getFirst(), dimType);
            Object rangeHigh = bounds.getSecond() == null ? null : ConvertUtils.convert(bounds.getSecond(), dimType);

            if (rangeLow == null) {
                // Any individual unbounded range causes the resulting interval to "bottom out" (union operation).
                dimLowerBound = nonEmptyMin;
            }
            else {
                dimLowerBound = dimLowerBound == null ? rangeLow : tiledbValueMin(dimLowerBound, rangeLow, dimension.getType());
            }

            if (rangeHigh == null) {
                // Any individual unbounded range causes the resulting interval to "bottom out" (union operation).
                dimUpperBound = nonEmptyMax;
            }
            else {
                dimUpperBound = dimUpperBound == null ? rangeHigh : tiledbValueMax(dimUpperBound, rangeHigh, dimension.getType());
            }
        }

        dimLowerBound = dimLowerBound == null ? nonEmptyMin : dimLowerBound;
        dimUpperBound = dimUpperBound == null ? nonEmptyMax : dimUpperBound;

        recordFunctionTime("getBoundsForDimension", timer);

        return new Pair<>(dimLowerBound, dimUpperBound);
    }

    /**
     * Gets a lower, upper bound pair from the given individual Presto range. If a value cannot be determined for either
     * end of the interval, null is set for that end in the result.
     */
    private Pair getBoundsForPrestoRange(Range range, Datatype type) throws TileDBError
    {
        Object dimLowerBound = null;
        Object dimUpperBound = null;

        if (range.isSingleValue()) {
            if (REAL.equals(range.getType()) && range.getType().getJavaType() == long.class) {
                Float val = intBitsToFloat(toIntExact((Long) range.getSingleValue()));
                dimLowerBound = val;
                dimUpperBound = val;
            }
            else if (isVarcharType(range.getType())) {
                String val = new String(((Slice) range.getSingleValue()).getBytes());
                dimLowerBound = val;
                dimUpperBound = val;
            }
            else {
                dimLowerBound = ConvertUtils.convert(range.getSingleValue(), getJavaType(type));
                dimUpperBound = dimLowerBound;
            }
        }
        else {
            Marker lowerBound = range.getLow();

            if (!lowerBound.isLowerUnbounded()) {
                Object lowerBoundValue = lowerBound.getValue();

                if (REAL.equals(range.getType()) && range.getType().getJavaType() == long.class) {
                    lowerBoundValue = intBitsToFloat(toIntExact((Long) lowerBoundValue));
                }
                else if (isVarcharType(range.getType())) {
                    lowerBoundValue = new String(((Slice) range.getLow().getValue()).getBytes());
                }
                else {
                    lowerBoundValue = ConvertUtils.convert(lowerBoundValue, getJavaType(type));
                }

                switch (lowerBound.getBound()) {
                    case ABOVE:
                        dimLowerBound = Util.addEpsilon(lowerBoundValue, type);
                        break;
                    case EXACTLY:
                        dimLowerBound = lowerBoundValue;
                        break;
                    case BELOW:
                        throw new IllegalArgumentException("Low marker should never use BELOW bound");
                    default:
                        throw new AssertionError("Unhandled bound: " + lowerBound.getBound());
                }
            }

            Marker upperBound = range.getHigh();

            if (!upperBound.isUpperUnbounded()) {
                Object upperBoundValue = upperBound.getValue();

                if (REAL.equals(upperBound.getType()) && upperBound.getType().getJavaType() == long.class) {
                    upperBoundValue = intBitsToFloat(toIntExact((Long) upperBoundValue));
                }
                else if (isVarcharType(range.getType())) {
                    upperBoundValue = new String(((Slice) range.getHigh().getValue()).getBytes());
                }
                else {
                    upperBoundValue = ConvertUtils.convert(upperBoundValue, getJavaType(type));
                }

                switch (upperBound.getBound()) {
                    case ABOVE:
                        throw new IllegalArgumentException("High marker should never use ABOVE bound");
                    case EXACTLY:
                        dimUpperBound = upperBoundValue;
                        break;
                    case BELOW:
                        dimUpperBound = Util.subtractEpsilon(upperBoundValue, type);
                        break;
                    default:
                        throw new AssertionError("Unhandled bound: " + upperBound.getBound());
                }
            }
        }

        return new Pair<>(dimLowerBound, dimUpperBound);
    }

    /**
     * Returns the minimum of the two given TileDB-typed values.
     */
    private static Object tiledbValueMin(Object a, Object b, Datatype type) throws TileDBError
    {
        switch (type) {
            case TILEDB_FLOAT32:
                return (float) a < (float) b ? a : b;
            case TILEDB_FLOAT64:
                return (double) a < (double) b ? a : b;
            case TILEDB_INT8:
                return (byte) a < (byte) b ? a : b;
            case TILEDB_INT16:
                return (short) a < (short) b ? a : b;
            case TILEDB_INT32:
                return (int) a < (int) b ? a : b;
            case TILEDB_DATETIME_AS:
            case TILEDB_DATETIME_FS:
            case TILEDB_DATETIME_PS:
            case TILEDB_DATETIME_NS:
            case TILEDB_DATETIME_US:
            case TILEDB_DATETIME_MS:
            case TILEDB_DATETIME_SEC:
            case TILEDB_DATETIME_MIN:
            case TILEDB_DATETIME_HR:
            case TILEDB_DATETIME_DAY:
            case TILEDB_DATETIME_WEEK:
            case TILEDB_DATETIME_MONTH:
            case TILEDB_DATETIME_YEAR:
            case TILEDB_INT64:
                return (long) a < (long) b ? a : b;
            case TILEDB_UINT8:
                return (short) a < (short) b ? a : b;
            case TILEDB_UINT16:
                return (int) a < (int) b ? a : b;
            case TILEDB_UINT32:
                return (long) a < (long) b ? a : b;
            case TILEDB_UINT64:
                return (long) a < (long) b ? a : b;
            case TILEDB_CHAR:
                return (byte) a < (byte) b ? a : b;
            default:
                throw new TileDBError("Unsupported TileDB Datatype enum: " + type);
        }
    }

    /**
     * Returns the maximum of the two given TileDB-typed values.
     */
    private static Object tiledbValueMax(Object a, Object b, Datatype type) throws TileDBError
    {
        switch (type) {
            case TILEDB_FLOAT32:
                return (float) a > (float) b ? a : b;
            case TILEDB_FLOAT64:
                return (double) a > (double) b ? a : b;
            case TILEDB_INT8:
                return (byte) a > (byte) b ? a : b;
            case TILEDB_INT16:
                return (short) a > (short) b ? a : b;
            case TILEDB_INT32:
                return (int) a > (int) b ? a : b;
            case TILEDB_DATETIME_AS:
            case TILEDB_DATETIME_FS:
            case TILEDB_DATETIME_PS:
            case TILEDB_DATETIME_NS:
            case TILEDB_DATETIME_US:
            case TILEDB_DATETIME_MS:
            case TILEDB_DATETIME_SEC:
            case TILEDB_DATETIME_MIN:
            case TILEDB_DATETIME_HR:
            case TILEDB_DATETIME_DAY:
            case TILEDB_DATETIME_WEEK:
            case TILEDB_DATETIME_MONTH:
            case TILEDB_DATETIME_YEAR:
            case TILEDB_INT64:
                return (long) a > (long) b ? a : b;
            case TILEDB_UINT8:
                return (short) a > (short) b ? a : b;
            case TILEDB_UINT16:
                return (int) a > (int) b ? a : b;
            case TILEDB_UINT32:
                return (long) a > (long) b ? a : b;
            case TILEDB_UINT64:
                return (long) a > (long) b ? a : b;
            case TILEDB_CHAR:
                return (byte) a > (byte) b ? a : b;
            default:
                throw new TileDBError("Unsupported TileDB Datatype enum: " + type);
        }
    }

    /**
     * Returns a column handle given the name.
     */
    private TileDBColumnHandle getColumnHandle(String columnName)
    {
        for (TileDBColumnHandle columnHandle : columnHandles) {
            if (columnHandle.getColumnName().equals(columnName)) {
                return columnHandle;
            }
        }
        return null;
    }

    /**
     * Reallocates the query buffers by doubling their allocations.
     */
    private void reallocateQueryBuffers() throws TileDBError
    {
        if (!canReallocBuffers()) {
            throw new TileDBError("Not enough memory to complete query!");
        }
        Pair<Long, Long> timer = startTimer();

        query.resetBuffers();

        for (int i = 0; i < queryBuffers.size(); i++) {
            Pair<NativeArray, NativeArray> nativeArrayPair = queryBuffers.get(i);
            NativeArray validityMap = validityMaps.get(i);
            NativeArray offsetBuffer = nativeArrayPair.getFirst();
            NativeArray valuesBuffer = nativeArrayPair.getSecond();
            if (valuesBuffer != null) {
                NativeArray tmp = new NativeArray(tileDBClient.getCtx(), 2 * valuesBuffer.getSize(), fieldTypes[i]);
                valuesBuffer.close();
                valuesBuffer = tmp;
            }
            if (offsetBuffer != null) {
                NativeArray tmp = new NativeArray(tileDBClient.getCtx(), 2 * offsetBuffer.getSize(), TILEDB_UINT64);
                offsetBuffer.close();
                offsetBuffer = tmp;
                if (validityMap != null) {
                    NativeArray newValidity = new NativeArray(tileDBClient.getCtx(), 2 * validityMap.getSize(), TILEDB_UINT8);
                    validityMap.close();
                    validityMap = newValidity;
                    query.setBufferNullable(columnHandles.get(i).getColumnName(), offsetBuffer, valuesBuffer, validityMap);
                    validityMaps.set(i, validityMap);
                }
                else {
                    query.setBuffer(columnHandles.get(i).getColumnName(), offsetBuffer, valuesBuffer);
                }
            }
            else {
                if (validityMap != null) {
                    NativeArray newValidity = new NativeArray(tileDBClient.getCtx(), 2 * validityMap.getSize(), TILEDB_UINT8);
                    validityMap.close();
                    validityMap = newValidity;
                    query.setBufferNullable(columnHandles.get(i).getColumnName(), valuesBuffer, validityMap);
                    validityMaps.set(i, validityMap);
                }
                else {
                    query.setBuffer(columnHandles.get(i).getColumnName(), valuesBuffer);
                }
            }
            queryBuffers.set(i, new Pair<>(offsetBuffer, valuesBuffer));
        }

        recordFunctionTime("reallocateQueryBuffers", timer);
    }

    @Override
    public long getReadTimeNanos()
    {
        return nanoStartTime > 0L ? (nanoEndTime == 0 ? System.nanoTime() : nanoEndTime) - nanoStartTime : 0L;
    }

    @Override
    public long getCompletedBytes()
    {
        return bytesRead;
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return calculateNativeArrayByteSizes();
    }

    @Override
    public Type getType(int field)
    {
        return columnHandles.get(field).getColumnType();
    }

    @Override
    public boolean advanceNextPosition()
    {
        Pair<Long, Long> timer = startTimer();

        if (closed) {
            return false;
        }

        if (numRecordsRead >= totalNumRecordsUB) {
            close();
            return false;
        }

        try {
            // If the cursor has passed the number of records from the previous query (or if this is the first time),
            // (re)submit the query.
            if (cursorPosition >= currentNumRecords - 1) {
                // If the query was completed, and we have exhausted all records then we should close the cursor
                if (queryStatus == TILEDB_COMPLETED) {
                    close();
                    return false;
                }
                do {
                    Pair<Long, Long> queryTiming = startTimer();
                    query.submit();
                    recordFunctionTime("query.submit()", queryTiming);

                    queryStatus = query.getQueryStatus();

                    // Compute the number of cells (records) that were returned by the query.
                    queryResultBufferElements = query.resultBufferElements();
                    currentNumRecords = 0;
                    for (Map.Entry<String, Pair<Long, Long>> resultElements : queryResultBufferElements.entrySet()) {
                        boolean isVar = false;
                        if (arraySchema.getAttributes().containsKey(resultElements.getKey())) {
                            try (Attribute attr = arraySchema.getAttribute(resultElements.getKey())) {
                                isVar = attr.isVar();
                            }
                        }
                        else {
                            try (io.tiledb.java.api.Domain domain = arraySchema.getDomain(); Dimension dim = domain.getDimension(resultElements.getKey())) {
                                isVar = dim.isVar();
                            }
                        }

                        if (isVar) {
                            currentNumRecords = resultElements.getValue().getFirst();
                        }
                        else {
                            currentNumRecords = resultElements.getValue().getSecond();
                        }
                        break;
                    }

                    // Increase the buffer allocation and resubmit if necessary.
                    if (queryStatus == TILEDB_INCOMPLETE && currentNumRecords == 0) {  // VERY IMPORTANT!!
                        reallocateQueryBuffers();
                    }
                    else if (currentNumRecords > 0) {
                        calculateAdditionalBytesRead();
                        cursorPosition = -1;
                        // Copy query result NativeArrays into Java arrays.
                        copyQueryBuffers();
                        // Break out of resubmit loop as we have some results.
                        break;
                    }
                } while (queryStatus == TILEDB_INCOMPLETE);
            }
        }
        catch (TileDBError tileDBError) {
            throw new PrestoException(TILEDB_RECORD_SET_ERROR, tileDBError);
        }

        cursorPosition++;
        numRecordsRead++;
        if (currentNumRecords == 0) {
            close();
        }

        recordFunctionTime("advanceNextPosition", timer);

        return currentNumRecords > 0;
    }

    /**
     * Function to calculate the bytes read based on the buffer sizes
     * @return byte in current buffers
     */
    private long calculateNativeArrayByteSizes()
    {
        long totalBufferSizes = 0;
        long bufferCount = 0;
        long largestSingleBuffer = 0;
        for (Pair<NativeArray, NativeArray> bufferPair : queryBuffers) {
            NativeArray offsets = bufferPair.getFirst();
            NativeArray values = bufferPair.getSecond();
            if (values != null) {
                totalBufferSizes += values.getNBytes();
                if (values.getNBytes() > largestSingleBuffer) {
                    largestSingleBuffer = values.getNBytes();
                }
                bufferCount++;
            }
            if (offsets != null) {
                totalBufferSizes += offsets.getNBytes();
                if (offsets.getNBytes() > largestSingleBuffer) {
                    largestSingleBuffer = offsets.getNBytes();
                }
            }
            bufferCount++;
        }
        LOG.debug("Largest single buffer is %d, total buffer count is %d", largestSingleBuffer, bufferCount);

        return totalBufferSizes;
    }

    /**
     * Check if we can double the buffer, or if there is not enough memory space
     * @return
     */
    private boolean canReallocBuffers()
    {
        long freeMemory = this.tileDBClient.getHardwareAbstractionLayer().getMemory().getAvailable();

        long totalBufferSizes = calculateNativeArrayByteSizes();

        LOG.info("Checking to realloc buffers from %d to %d with %d memory free", totalBufferSizes, 2 * totalBufferSizes, freeMemory);

        // If we are going to double the buffers we need to make sure we have 4x space for
        // doubling the native buffer and copying to java arrays
        return freeMemory > (4 * totalBufferSizes);
    }

    /**
     * Compute the number of bytes read in this iteration of query.submit()
     */
    private void calculateAdditionalBytesRead()
    {
        if (queryBuffers.size() == 0) {
            return;
        }
        Pair<Long, Long> timer = startTimer();
        for (Map.Entry<String, Pair<Long, Long>> bufferElement : queryResultBufferElements.entrySet()) {
            String bufferName = bufferElement.getKey();
            int index = columnIndexLookup.get(bufferName);
            Pair<NativeArray, NativeArray> bufferPair = queryBuffers.get(index);
            if (bufferPair != null && bufferPair.getFirst() != null) {
                bytesRead += bufferElement.getValue().getFirst() * bufferPair.getFirst().getNativeTypeSize();
            }
            if (bufferPair != null && bufferPair.getSecond() != null) {
                bytesRead += bufferElement.getValue().getSecond() * bufferPair.getSecond().getNativeTypeSize();
            }
        }

        recordFunctionTime("calculateAdditionalBytesRead", timer);
    }

    /**
     * Copy buffers from NativeArray to java arrays for faster access times
     * Making the JNI calls in NativeArray is too slow
     * @throws TileDBError
     */
    private void copyQueryBuffers() throws TileDBError
    {
        Pair<Long, Long> timer = startTimer();
        for (Map.Entry<String, Pair<Long, Long>> entry : queryResultBufferElements.entrySet()) {
            String attributeName = entry.getKey();
            int queryBufferIdx = columnIndexLookup.get(attributeName);
            if (entry.getValue().getFirst() == 0) {
                queryResultArrays.set(queryBufferIdx, new Pair<>(null, query.getBuffer(attributeName)));
            }
            else {
                queryResultArrays.set(queryBufferIdx, new Pair<>(query.getVarBuffer(attributeName), query.getBuffer(attributeName)));
            }
        }
        recordFunctionTime("copyQueryBuffers", timer);
    }

    @Override
    public boolean getBoolean(int field)
    {
        Pair<Long, Long> timer = startTimer();
        boolean value;
        // Check and handle dimension
        if (columnHandles.get(field).getIsDimension()) {
            value = false;
        }
        else {
            value = ((int[]) queryResultArrays.get(field).getSecond())[cursorPosition] != 0;
        }

        recordFunctionTime("getBoolean", timer);

        return value;
    }

    @Override
    public long getLong(int field)
    {
        Pair<Long, Long> timer = startTimer();
        long value = 0;
        int index = cursorPosition;
        Datatype datatype = fieldTypes[field];

        OffsetDateTime ms;

        Object fieldArray = queryResultArrays.get(field).getSecond();
        switch (datatype) {
            case TILEDB_INT8: {
                value = ((byte[]) fieldArray)[index];
                break;
            }
            case TILEDB_UINT8:
            case TILEDB_INT16: {
                value = ((short[]) fieldArray)[index];
                break;
            }
            case TILEDB_UINT16:
            case TILEDB_INT32: {
                value = ((int[]) fieldArray)[index];
                break;
            }
            case TILEDB_DATETIME_AS: {
                value = ((long[]) fieldArray)[index] / 1000000000000000L;
                break;
            }
            case TILEDB_DATETIME_FS: {
                value = ((long[]) fieldArray)[index] / 1000000000000L;
                break;
            }
            case TILEDB_DATETIME_PS: {
                value = ((long[]) fieldArray)[index] / 1000000000;
                break;
            }
            case TILEDB_DATETIME_NS: {
                value = ((long[]) fieldArray)[index] / 1000000;
                break;
            }
            case TILEDB_DATETIME_US: {
                value = ((long[]) fieldArray)[index] / 1000;
                break;
            }
            case TILEDB_DATETIME_SEC: {
                value = ((long[]) fieldArray)[index] * 1000;
                break;
            }
            case TILEDB_DATETIME_MIN: {
                value = ((long[]) fieldArray)[index] * 60 * 1000;
                break;
            }
            case TILEDB_DATETIME_HR: {
                value = ((long[]) fieldArray)[index] * 60 * 60 * 1000;
                break;
            }
            case TILEDB_DATETIME_WEEK: {
                ms = zeroDateTime.toInstant().atOffset(ZoneOffset.UTC).plusWeeks(((long[]) fieldArray)[index]);
                value = ChronoUnit.DAYS.between(zeroDateTime, ms);
                break;
            }
            case TILEDB_DATETIME_MONTH: {
                ms = zeroDateTime.toInstant().atOffset(ZoneOffset.UTC).plusMonths(((long[]) fieldArray)[index]);
                value = ChronoUnit.DAYS.between(zeroDateTime, ms);
                break;
            }
            case TILEDB_DATETIME_YEAR: {
                ms = zeroDateTime.toInstant().atOffset(ZoneOffset.UTC).plusYears(((long[]) fieldArray)[index]);
                value = ChronoUnit.DAYS.between(zeroDateTime, ms);
                break;
            }
            case TILEDB_DATETIME_MS:
            case TILEDB_DATETIME_DAY:
            case TILEDB_UINT32:
            case TILEDB_UINT64:
            case TILEDB_INT64: {
                value = ((long[]) fieldArray)[index];
                break;
            }
            // Presto converts 32bit floats to long types
            case TILEDB_FLOAT32: {
                value = ((Integer) floatToRawIntBits(((float[]) fieldArray)[index])).longValue();
                break;
            }
            default:
                LOG.error("Unsupported type: " + datatype + " returning: " + value);
        }

        recordFunctionTime("getLong", timer);

        return value;
    }

    @Override
    public double getDouble(int field)
    {
        Pair<Long, Long> timer = startTimer();
        double value = 0;
        String bufferName;
        int index = cursorPosition;
        Datatype datatype = fieldTypes[field];

        Object fieldArray = queryResultArrays.get(field).getSecond();
        switch (datatype) {
            case TILEDB_FLOAT32: {
                value = (double) ((float[]) fieldArray)[index];
                break;
            }
            case TILEDB_FLOAT64: {
                value = ((double[]) fieldArray)[index];
                break;
            }
        }

        recordFunctionTime("getDouble", timer);

        return value;
    }

    @Override
    public Slice getSlice(int field)
    {
        Pair<Long, Long> timer = startTimer();
        Slice value;
        int startPosition = cursorPosition;
        String bufferName = columnHandles.get(field).getColumnName();

        Pair<Object, Object> buffer = queryResultArrays.get(field);
        byte[] nativeArrayValues = (byte[]) buffer.getSecond();
        long[] nativeArrayOffsets = (long[]) buffer.getFirst();

        int endPosition = cursorPosition + 1;
        if (nativeArrayOffsets != null) {
            // If its not the first value, we need to see where the previous position ended to know where to start.
            if (startPosition > 0) {
                startPosition = (int) nativeArrayOffsets[cursorPosition];
            }
            // If the current position is equal to the number of results - 1 then we are at the last varchar value
            if (cursorPosition >= nativeArrayOffsets.length - 1) {
                endPosition = nativeArrayValues.length;
            }
            else { // Else read the end from the next offset.
                endPosition = (int) nativeArrayOffsets[cursorPosition + 1];
            }
        }
        value = Slices.wrappedBuffer(nativeArrayValues, startPosition, endPosition - startPosition);

        recordFunctionTime("getSlice", timer);

        return value;
    }

    @Override
    public Object getObject(int field)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isNull(int field)
    {
        short[] validityByteMap;
        boolean nullable = fieldNullables[field];
        String name = fieldNames[field];
        if (nullable) {
            try {
                validityByteMap = query.getValidityByteMap(name);
                return validityByteMap[cursorPosition] == 0;
            }
            catch (TileDBError tileDBError) {
                LOG.error("Error retrieving validity map of attribute: " + name);
            }
        }
        return false;
    }

    @SuppressWarnings("UnusedDeclaration")
    @Override
    public void close()
    {
        Pair<Long, Long> timer = startTimer();
        if (closed) {
            return;
        }
        closed = true;
        nanoEndTime = System.nanoTime();
        closeQueryNativeArrays();
        queryBuffers.clear();
        queryResultArrays.clear();
        if (subArray != null) {
            subArray.close();
        }

        arraySchema.close();
        query.close();
        array.close();

        recordFunctionTime("close", timer);
        reportFunctionTimes();
    }

    /**
     * Close out all the NativeArray objects
     */
    private void closeQueryNativeArrays()
    {
        for (Pair<NativeArray, NativeArray> bufferSet : queryBuffers) {
            if (bufferSet == null) {
                continue;
            }
            NativeArray offsetArray = bufferSet.getFirst();
            NativeArray valuesArray = bufferSet.getSecond();
            if (offsetArray != null) {
                offsetArray.close();
            }
            if (valuesArray != null) {
                valuesArray.close();
            }
        }
    }

    private Pair<Long, Long> startTimer()
    {
        if (functionTimingEnabled) {
            return new Pair<>(System.nanoTime(), 0L);
        }
        else {
            return null;
        }
    }

    /**
     * Save function timing to the hashmap of times
     * @param functionName Name of function being recorded
     * @param timer Times from record
     */
    private void recordFunctionTime(String functionName, Pair<Long, Long> timer)
    {
        if (!functionTimingEnabled) {
            return;
        }

        timer.setSecond(System.nanoTime());
        List<Pair<Long, Long>> ft = functionTimings.get(functionName);
        if (ft == null) {
            ft = new ArrayList<Pair<Long, Long>>();
        }
        ft.add(timer);
        functionTimings.put(functionName, ft);
    }

    /**
     * Calculate and report function times to the log
     */
    private void reportFunctionTimes()
    {
        if (!functionTimingEnabled) {
            return;
        }

        LOG.info("functionName, total (sec), avg (sec), median, min, max, count");
        StringBuilder data = new StringBuilder();
        for (Map.Entry<String, List<Pair<Long, Long>>> entry : functionTimings.entrySet()) {
            String functionName = entry.getKey();
            List<Long> durations = new ArrayList<>();
            for (Pair<Long, Long> timings : entry.getValue()) {
                durations.add(timings.getSecond() - timings.getFirst());
            }
            Collections.sort(durations);
            long total = durations.stream().mapToLong(a -> a).sum();
            long median = durations.get(durations.size() / 2);
            OptionalDouble avg = durations.stream().mapToDouble(a -> a).average();
            data.append(format("%s,%f,%f,%d,%d,%d,%d\n", functionName, total / 1000000000.0, avg.getAsDouble() / 1000000000.0, median, durations.get(0), durations.get(durations.size() - 1), durations.size()));
        }
        double totalReadTime = getReadTimeNanos() / 1000000000.0;
        data.append(format("%s,%f,%f,%f,%f,%f,%d\n", "totalReadTime", totalReadTime, totalReadTime, totalReadTime, totalReadTime, totalReadTime, 1));
        LOG.info(data.toString());
        try {
            File tmpBaseDir = new File(System.getProperty("java.io.tmpdir"));
            Path statsFile = Paths.get(tmpBaseDir.getPath(), "tiledb_stats_" + queryId);
            Stats.dump(statsFile.toString());
            LOG.info("Dumped tiledb stats to %s", statsFile.toString());
        }
        catch (TileDBError tileDBError) {
            tileDBError.printStackTrace();
        }
    }
}
