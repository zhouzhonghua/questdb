/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.*;
import io.questdb.cairo.map.RecordValueSinkFactory;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.engine.EmptyTableRecordCursorFactory;
import io.questdb.griffin.engine.LimitRecordCursorFactory;
import io.questdb.griffin.engine.functions.columns.SymbolColumn;
import io.questdb.griffin.engine.functions.constants.LongConstant;
import io.questdb.griffin.engine.groupby.*;
import io.questdb.griffin.engine.join.*;
import io.questdb.griffin.engine.orderby.RecordComparatorCompiler;
import io.questdb.griffin.engine.orderby.SortedLightRecordCursorFactory;
import io.questdb.griffin.engine.orderby.SortedRecordCursorFactory;
import io.questdb.griffin.engine.table.*;
import io.questdb.griffin.engine.union.UnionAllRecordCursorFactory;
import io.questdb.griffin.engine.union.UnionRecordCursorFactory;
import io.questdb.griffin.model.*;
import io.questdb.std.*;
import org.jetbrains.annotations.NotNull;

import static io.questdb.griffin.model.ExpressionNode.FUNCTION;

public class SqlCodeGenerator {
    private static final IntHashSet limitTypes = new IntHashSet();
    private final WhereClauseParser filterAnalyser = new WhereClauseParser();
    private final FunctionParser functionParser;
    private final CairoEngine engine;
    private final BytecodeAssembler asm = new BytecodeAssembler();
    // this list is used to generate record sinks
    private final ListColumnFilter listColumnFilterA = new ListColumnFilter();
    private final ListColumnFilter listColumnFilterB = new ListColumnFilter();
    private final CairoConfiguration configuration;
    private final RecordComparatorCompiler recordComparatorCompiler;
    private final IntHashSet intHashSet = new IntHashSet();
    private final ArrayColumnTypes keyTypes = new ArrayColumnTypes();
    private final ArrayColumnTypes valueTypes = new ArrayColumnTypes();
    private final EntityColumnFilter entityColumnFilter = new EntityColumnFilter();
    private boolean fullFatJoins = false;
    public SqlCodeGenerator(
            CairoEngine engine,
            CairoConfiguration configuration,
            FunctionParser functionParser
    ) {
        this.engine = engine;
        this.configuration = configuration;
        this.functionParser = functionParser;
        this.recordComparatorCompiler = new RecordComparatorCompiler(asm);
    }

    private GenericRecordMetadata copyMetadata(RecordMetadata that) {
        // todo: this metadata is immutable. Ideally we shouldn't be creating metadata for the same table over and over
        return GenericRecordMetadata.copyOf(that);
    }

    private RecordCursorFactory createAsOfJoin(
            RecordMetadata metadata,
            RecordCursorFactory master,
            RecordSink masterKeySink,
            RecordCursorFactory slave,
            RecordSink slaveKeySink,
            int columnSplit
    ) {
        valueTypes.reset();
        valueTypes.add(ColumnType.LONG);
        valueTypes.add(ColumnType.LONG);

        return new AsOfJoinLightRecordCursorFactory(
                configuration,
                metadata,
                master,
                slave,
                keyTypes,
                valueTypes,
                masterKeySink,
                slaveKeySink,
                columnSplit
        );
    }

    @NotNull
    private RecordCursorFactory createFullFatAsOfJoin(
            RecordCursorFactory master,
            RecordMetadata masterMetadata,
            CharSequence masterAlias,
            RecordCursorFactory slave,
            RecordMetadata slaveMetadata,
            CharSequence slaveAlias,
            int joinPosition
    ) throws SqlException {

        // create hash set of key columns to easily find them
        intHashSet.clear();
        for (int i = 0, n = listColumnFilterA.getColumnCount(); i < n; i++) {
            intHashSet.add(listColumnFilterA.getColumnIndex(i));
        }


        // map doesn't support variable length types in map value, which is ok
        // when we join tables on strings - technically string is the key
        // and we do not need to store it in value, but we will still reject
        //
        // never mind, this is a stop-gap measure until I understand the problem
        // fully

        for (int k = 0, m = slaveMetadata.getColumnCount(); k < m; k++) {
            if (intHashSet.excludes(k)) {
                int type = slaveMetadata.getColumnType(k);
                if (type == ColumnType.STRING || type == ColumnType.BINARY) {
                    throw SqlException
                            .position(joinPosition).put("right side column '")
                            .put(slaveMetadata.getColumnName(k)).put("' is of unsupported type");
                }
            }
        }

        RecordSink masterSink = RecordSinkFactory.getInstance(
                asm,
                masterMetadata,
                listColumnFilterB,
                true
        );

        JoinRecordMetadata metadata = new JoinRecordMetadata(
                configuration,
                masterMetadata.getColumnCount() + slaveMetadata.getColumnCount()
        );

        // metadata will have master record verbatim
        metadata.copyColumnMetadataFrom(masterAlias, masterMetadata);

        // slave record is split across key and value of map
        // the rationale is not to store columns twice
        // especially when map value does not support variable
        // length types


        final IntList columnIndex = new IntList(slaveMetadata.getColumnCount());
        // In map record value columns go first, so at this stage
        // we add to metadata all slave columns that are not keys.
        // Add same columns to filter while we are in this loop.
        listColumnFilterB.clear();
        valueTypes.reset();
        ArrayColumnTypes slaveTypes = new ArrayColumnTypes();
        for (int i = 0, n = slaveMetadata.getColumnCount(); i < n; i++) {
            if (intHashSet.excludes(i)) {
                int type = slaveMetadata.getColumnType(i);
                metadata.add(slaveAlias, slaveMetadata.getColumnName(i), type);
                listColumnFilterB.add(i);
                columnIndex.add(i);
                valueTypes.add(type);
                slaveTypes.add(type);
            }
        }

        // now add key columns to metadata
        for (int i = 0, n = listColumnFilterA.getColumnCount(); i < n; i++) {
            int index = listColumnFilterA.getColumnIndex(i);
            int type = slaveMetadata.getColumnType(index);
            if (type == ColumnType.SYMBOL) {
                type = ColumnType.STRING;
            }
            metadata.add(slaveAlias, slaveMetadata.getColumnName(index), type);
            columnIndex.add(index);
            slaveTypes.add(type);
        }

        if (masterMetadata.getTimestampIndex() != -1) {
            metadata.setTimestampIndex(masterMetadata.getTimestampIndex());
        }

        master = new AsOfJoinRecordCursorFactory(
                configuration,
                metadata,
                master,
                slave,
                keyTypes,
                valueTypes,
                slaveTypes,
                masterSink,
                RecordSinkFactory.getInstance(
                        asm,
                        slaveMetadata,
                        listColumnFilterA,
                        true
                ),
                masterMetadata.getColumnCount(),
                RecordValueSinkFactory.getInstance(asm, slaveMetadata, listColumnFilterB),
                columnIndex
        );
        return master;
    }

    private RecordCursorFactory createHashJoin(
            RecordMetadata metadata,
            RecordCursorFactory master,
            RecordCursorFactory slave,
            int joinType
    ) {
        /*
         * JoinContext provides the following information:
         * a/bIndexes - index of model where join column is coming from
         * a/bNames - name of columns in respective models, these column names are not prefixed with table aliases
         * a/bNodes - the original column references, that can include table alias. Sometimes it doesn't when column name is unambiguous
         *
         * a/b are "inverted" in that "a" for slave and "b" for master
         *
         * The issue is when we use model indexes and vanilla column names they would only work on single-table
         * record cursor but original names with prefixed columns will only work with JoinRecordMetadata
         */
        final RecordMetadata masterMetadata = master.getMetadata();
        final RecordMetadata slaveMetadata = slave.getMetadata();
        final RecordSink masterKeySink = RecordSinkFactory.getInstance(
                asm,
                masterMetadata,
                listColumnFilterB,
                true
        );

        final RecordSink slaveKeySink = RecordSinkFactory.getInstance(
                asm,
                slaveMetadata,
                listColumnFilterA,
                true
        );

        valueTypes.reset();
        valueTypes.add(ColumnType.LONG);
        valueTypes.add(ColumnType.LONG);

        if (slave.isRandomAccessCursor() && !fullFatJoins) {
            if (joinType == QueryModel.JOIN_INNER) {
                return new HashJoinLightRecordCursorFactory(
                        configuration,
                        metadata,
                        master,
                        slave,
                        keyTypes,
                        valueTypes,
                        masterKeySink,
                        slaveKeySink,
                        masterMetadata.getColumnCount()
                );
            }

            return new HashOuterJoinLightRecordCursorFactory(
                    configuration,
                    metadata,
                    master,
                    slave,
                    keyTypes,
                    valueTypes,
                    masterKeySink,
                    slaveKeySink,
                    masterMetadata.getColumnCount()
            );
        }

        entityColumnFilter.of(slaveMetadata.getColumnCount());
        RecordSink slaveSink = RecordSinkFactory.getInstance(
                asm,
                slaveMetadata,
                entityColumnFilter,
                false
        );

        if (joinType == QueryModel.JOIN_INNER) {
            return new HashJoinRecordCursorFactory(
                    configuration,
                    metadata,
                    master,
                    slave,
                    keyTypes,
                    valueTypes,
                    masterKeySink,
                    slaveKeySink,
                    slaveSink,
                    masterMetadata.getColumnCount()
            );
        }

        return new HashOuterJoinRecordCursorFactory(
                configuration,
                metadata,
                master,
                slave,
                keyTypes,
                valueTypes,
                masterKeySink,
                slaveKeySink,
                slaveSink,
                masterMetadata.getColumnCount()
        );
    }

    @NotNull
    private JoinRecordMetadata createJoinMetadata(
            CharSequence masterAlias,
            RecordMetadata masterMetadata,
            CharSequence slaveAlias,
            RecordMetadata slaveMetadata
    ) {
        return createJoinMetadata(
                masterAlias,
                masterMetadata,
                slaveAlias,
                slaveMetadata,
                masterMetadata.getTimestampIndex()
        );
    }

    @NotNull
    private JoinRecordMetadata createJoinMetadata(
            CharSequence masterAlias,
            RecordMetadata masterMetadata,
            CharSequence slaveAlias,
            RecordMetadata slaveMetadata,
            int timestampIndex
    ) {
        JoinRecordMetadata metadata;
        metadata = new JoinRecordMetadata(
                configuration,
                masterMetadata.getColumnCount() + slaveMetadata.getColumnCount()
        );

        metadata.copyColumnMetadataFrom(masterAlias, masterMetadata);
        metadata.copyColumnMetadataFrom(slaveAlias, slaveMetadata);

        if (timestampIndex != -1) {
            metadata.setTimestampIndex(timestampIndex);
        }
        return metadata;
    }

    private RecordCursorFactory createSpliceJoin(
            RecordMetadata metadata,
            RecordCursorFactory master,
            RecordSink masterKeySink,
            RecordCursorFactory slave,
            RecordSink slaveKeySink,
            int columnSplit
    ) {
        valueTypes.reset();
        valueTypes.add(ColumnType.LONG); // master previous
        valueTypes.add(ColumnType.LONG); // master current
        valueTypes.add(ColumnType.LONG); // slave previous
        valueTypes.add(ColumnType.LONG); // slave current

        return new SpliceJoinLightRecordCursorFactory(
                configuration,
                metadata,
                master,
                slave,
                keyTypes,
                valueTypes,
                masterKeySink,
                slaveKeySink,
                columnSplit
        );
    }

    RecordCursorFactory generate(QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        return generateQuery(model, executionContext, true);
    }

    private RecordCursorFactory generateFunctionQuery(
            QueryModel model,
            SqlExecutionContext executionContext
    ) throws SqlException {
        final Function function = model.getTableNameFunction();
        assert function != null;
        if (function.getType() != TypeEx.CURSOR) {
            throw SqlException.position(model.getTableName().position).put("function must return CURSOR [actual=").put(ColumnType.nameOf(function.getType())).put(']');
        }

        RecordCursorFactory factory = function.getRecordCursorFactory();

        // check if there are post-filters
        ExpressionNode filter = model.getWhereClause();
        if (filter != null) {
            factory = new FilteredRecordCursorFactory(
                    factory,
                    functionParser.parseFunction(filter, factory.getMetadata(), executionContext)
            );
        }

        return factory;
    }

    private RecordCursorFactory generateJoins(QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        final ObjList<QueryModel> joinModels = model.getJoinModels();
        IntList ordered = model.getOrderedJoinModels();
        RecordCursorFactory master = null;
        CharSequence masterAlias = null;

        try {
            int n = ordered.size();
            assert n > 0;
            for (int i = 0; i < n; i++) {
                int index = ordered.getQuick(i);
                QueryModel slaveModel = joinModels.getQuick(index);

                // compile
                RecordCursorFactory slave = generateQuery(slaveModel, executionContext, i > 0);

                // check if this is the root of joins
                if (master == null) {
                    // This is an opportunistic check of order by clause
                    // to determine if we can get away ordering main record source only
                    // Ordering main record source could benefit from rowid access thus
                    // making it faster compared to ordering of join record source that
                    // doesn't allow rowid access.
                    master = slave;
                    masterAlias = slaveModel.getName();
                } else {
                    // not the root, join to "master"
                    final int joinType = slaveModel.getJoinType();
                    final RecordMetadata masterMetadata = master.getMetadata();
                    final RecordMetadata slaveMetadata = slave.getMetadata();

                    switch (joinType) {
                        case QueryModel.JOIN_CROSS:
                            return new CrossJoinRecordCursorFactory(
                                    createJoinMetadata(masterAlias, masterMetadata, slaveModel.getName(), slaveMetadata),
                                    master,
                                    slave,
                                    masterMetadata.getColumnCount()
                            );
                        case QueryModel.JOIN_ASOF:
                            validateBothTimestamps(slaveModel, masterMetadata, slaveMetadata);
                            processJoinContext(index == 1, slaveModel.getContext(), masterMetadata, slaveMetadata);
                            if (slave.isRandomAccessCursor() && !fullFatJoins) {
                                if (listColumnFilterA.size() > 0 && listColumnFilterB.size() > 0) {
                                    master = createAsOfJoin(
                                            createJoinMetadata(masterAlias, masterMetadata, slaveModel.getName(), slaveMetadata),
                                            master,
                                            RecordSinkFactory.getInstance(
                                                    asm,
                                                    masterMetadata,
                                                    listColumnFilterB,
                                                    true
                                            ),
                                            slave,
                                            RecordSinkFactory.getInstance(
                                                    asm,
                                                    slaveMetadata,
                                                    listColumnFilterA,
                                                    true
                                            ),
                                            masterMetadata.getColumnCount()
                                    );
                                } else {
                                    master = new AsOfJoinNoKeyRecordCursorFactory(
                                            createJoinMetadata(masterAlias, masterMetadata, slaveModel.getName(), slaveMetadata),
                                            master,
                                            slave,
                                            masterMetadata.getColumnCount()
                                    );
                                }
                            } else {
                                master = createFullFatAsOfJoin(
                                        master,
                                        masterMetadata,
                                        masterAlias,
                                        slave,
                                        slaveMetadata,
                                        slaveModel.getName(),
                                        slaveModel.getJoinKeywordPosition()
                                );
                            }
                            masterAlias = null;
                            break;
                        case QueryModel.JOIN_SPLICE:
                            validateBothTimestamps(slaveModel, masterMetadata, slaveMetadata);
                            processJoinContext(index == 1, slaveModel.getContext(), masterMetadata, slaveMetadata);
                            if (slave.isRandomAccessCursor() && master.isRandomAccessCursor() && !fullFatJoins) {
                                master = createSpliceJoin(
                                        // splice join result does not have timestamp
                                        createJoinMetadata(masterAlias, masterMetadata, slaveModel.getName(), slaveMetadata, -1),
                                        master,
                                        RecordSinkFactory.getInstance(
                                                asm,
                                                masterMetadata,
                                                listColumnFilterB,
                                                true
                                        ),
                                        slave,
                                        RecordSinkFactory.getInstance(
                                                asm,
                                                slaveMetadata,
                                                listColumnFilterA,
                                                true
                                        ),
                                        masterMetadata.getColumnCount()
                                );
                            } else {
                                assert false;
                            }
                            break;
                        default:
                            processJoinContext(index == 1, slaveModel.getContext(), masterMetadata, slaveMetadata);
                            master = createHashJoin(
                                    createJoinMetadata(masterAlias, masterMetadata, slaveModel.getName(), slaveMetadata),
                                    master,
                                    slave,
                                    joinType
                            );
                            masterAlias = null;
                            break;
                    }
                }

                // check if there are post-filters
                ExpressionNode filter = slaveModel.getPostJoinWhereClause();
                if (filter != null) {
                    master = new FilteredRecordCursorFactory(master, functionParser.parseFunction(filter, master.getMetadata(), executionContext));
                }
            }

            // unfortunately we had to go all out to create join metadata
            // now it is time to check if we have constant conditions
            ExpressionNode constFilter = model.getConstWhereClause();
            if (constFilter != null) {
                Function function = functionParser.parseFunction(constFilter, null, executionContext);
                if (!function.getBool(null)) {
                    // do not copy metadata here
                    // this would have been JoinRecordMetadata, which is new instance anyway
                    // we have to make sure that this metadata is safely transitioned
                    // to empty cursor factory
                    JoinRecordMetadata metadata = (JoinRecordMetadata) master.getMetadata();
                    metadata.incrementRefCount();
                    RecordCursorFactory factory = new EmptyTableRecordCursorFactory(metadata);
                    Misc.free(master);
                    return factory;
                }
            }
            return master;
        } catch (CairoException | SqlException e) {
            Misc.free(master);
            throw e;
        }
    }

    private void validateBothTimestamps(QueryModel slaveModel, RecordMetadata masterMetadata, RecordMetadata slaveMetadata) throws SqlException {
        if (masterMetadata.getTimestampIndex() == -1) {
            throw SqlException.$(slaveModel.getJoinKeywordPosition(), "left side of time series join has no timestamp");
        }

        if (slaveMetadata.getTimestampIndex() == -1) {
            throw SqlException.$(slaveModel.getJoinKeywordPosition(), "right side of time series join has no timestamp");
        }
    }

    @NotNull
    private RecordCursorFactory generateLatestByQuery(
            QueryModel model,
            TableReader reader,
            RecordMetadata metadata,
            String tableName,
            IntrinsicModel intrinsicModel,
            Function filter,
            SqlExecutionContext executionContext
    ) throws SqlException {
        final DataFrameCursorFactory dataFrameCursorFactory;
        if (intrinsicModel.intervals != null) {
            dataFrameCursorFactory = new IntervalBwdDataFrameCursorFactory(engine, tableName, model.getTableVersion(), intrinsicModel.intervals);
        } else {
            dataFrameCursorFactory = new FullBwdDataFrameCursorFactory(engine, tableName, model.getTableVersion());
        }

        if (listColumnFilterA.size() == 1) {
            final int latestByIndex = listColumnFilterA.getColumnIndex(0);
            final boolean indexed = metadata.isColumnIndexed(latestByIndex);

            if (intrinsicModel.keyColumn != null) {
                // key column must always be the same as latest by column
                assert latestByIndex == metadata.getColumnIndexQuiet(intrinsicModel.keyColumn);

                if (intrinsicModel.keySubQuery != null) {

                    final RecordCursorFactory rcf = generate(intrinsicModel.keySubQuery, executionContext);
                    final int firstColumnType = validateSubQueryColumnAndGetType(intrinsicModel, rcf.getMetadata());

                    return new LatestBySubQueryRecordCursorFactory(
                            configuration,
                            metadata,
                            dataFrameCursorFactory,
                            latestByIndex,
                            rcf,
                            filter,
                            indexed,
                            firstColumnType
                    );
                }

                final int nKeyValues = intrinsicModel.keyValues.size();
                if (indexed) {

                    assert nKeyValues > 0;
                    // deal with key values as a list
                    // 1. resolve each value of the list to "int"
                    // 2. get first row in index for each value (stream)

                    final SymbolMapReader symbolMapReader = reader.getSymbolMapReader(latestByIndex);
                    final RowCursorFactory rcf;
                    if (nKeyValues == 1) {
                        final CharSequence symbolValue = intrinsicModel.keyValues.get(0);
                        final int symbol = symbolMapReader.getQuick(symbolValue);

                        if (filter == null) {
                            if (symbol == SymbolTable.VALUE_NOT_FOUND) {
                                rcf = new LatestByValueDeferredIndexedRowCursorFactory(latestByIndex, Chars.toString(symbolValue), false);
                            } else {
                                rcf = new LatestByValueIndexedRowCursorFactory(latestByIndex, symbol, false);
                            }
                            return new DataFrameRecordCursorFactory(copyMetadata(metadata), dataFrameCursorFactory, rcf, null);
                        }

                        if (symbol == SymbolTable.VALUE_NOT_FOUND) {
                            return new LatestByValueDeferredIndexedFilteredRecordCursorFactory(
                                    copyMetadata(metadata),
                                    dataFrameCursorFactory,
                                    latestByIndex,
                                    Chars.toString(symbolValue),
                                    filter);
                        }
                        return new LatestByValueIndexedFilteredRecordCursorFactory(
                                copyMetadata(metadata),
                                dataFrameCursorFactory,
                                latestByIndex,
                                symbol,
                                filter);
                    }

                    return new LatestByValuesIndexedFilteredRecordCursorFactory(
                            configuration,
                            copyMetadata(metadata),
                            dataFrameCursorFactory,
                            latestByIndex,
                            intrinsicModel.keyValues,
                            symbolMapReader,
                            filter
                    );
                }

                assert nKeyValues > 0;

                // we have "latest by" column values, but no index
                final SymbolMapReader symbolMapReader = reader.getSymbolMapReader(latestByIndex);

                if (nKeyValues > 1) {
                    return new LatestByValuesFilteredRecordCursorFactory(
                            configuration,
                            copyMetadata(metadata),
                            dataFrameCursorFactory,
                            latestByIndex,
                            intrinsicModel.keyValues,
                            symbolMapReader,
                            filter
                    );
                }

                // we have a single symbol key
                int symbolKey = symbolMapReader.getQuick(intrinsicModel.keyValues.get(0));
                if (symbolKey == SymbolTable.VALUE_NOT_FOUND) {
                    return new LatestByValueDeferredFilteredRecordCursorFactory(
                            copyMetadata(metadata),
                            dataFrameCursorFactory,
                            latestByIndex,
                            Chars.toString(intrinsicModel.keyValues.get(0)),
                            filter
                    );
                }

                return new LatestByValueFilteredRecordCursorFactory(copyMetadata(metadata), dataFrameCursorFactory, latestByIndex, symbolKey, filter);
            }
            // we select all values of "latest by" column

            assert intrinsicModel.keyValues.size() == 0;
            // get latest rows for all values of "latest by" column

            if (indexed) {
                return new LatestByAllIndexedFilteredRecordCursorFactory(
                        configuration,
                        copyMetadata(metadata),
                        dataFrameCursorFactory,
                        latestByIndex,
                        filter);
            }
        }

        return new LatestByAllFilteredRecordCursorFactory(
                copyMetadata(metadata),
                configuration,
                dataFrameCursorFactory,
                RecordSinkFactory.getInstance(asm, metadata, listColumnFilterA, false),
                keyTypes,
                filter
        );
    }

    private RecordCursorFactory generateLimit(RecordCursorFactory factory, QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        ExpressionNode limitLo = model.getLimitLo();
        ExpressionNode limitHi = model.getLimitHi();

        if (limitLo == null && limitHi == null) {
            return factory;
        }

        final Function loFunc;
        final Function hiFunc;

        if (limitLo == null) {
            loFunc = new LongConstant(0, 0L);
        } else {
            loFunc = functionParser.parseFunction(limitLo, EmptyRecordMetadata.INSTANCE, executionContext);
            final int type = loFunc.getType();
            if (limitTypes.excludes(type)) {
                throw SqlException.$(limitLo.position, "invalid type: ").put(ColumnType.nameOf(type));
            }
        }

        if (limitHi != null) {
            hiFunc = functionParser.parseFunction(limitHi, EmptyRecordMetadata.INSTANCE, executionContext);
            final int type = hiFunc.getType();
            if (limitTypes.excludes(type)) {
                throw SqlException.$(limitHi.position, "invalid type: ").put(ColumnType.nameOf(type));
            }
        } else {
            hiFunc = null;
        }
        return new LimitRecordCursorFactory(factory, loFunc, hiFunc);
    }

    private RecordCursorFactory generateNoSelect(
            QueryModel model,
            SqlExecutionContext executionContext
    ) throws SqlException {
        ExpressionNode tableName = model.getTableName();
        if (tableName != null) {
            if (tableName.type == FUNCTION) {
                return generateFunctionQuery(model, executionContext);
            } else {
                return generateTableQuery(model, executionContext);
            }
        }

        final RecordCursorFactory factory = generateSubQuery(model, executionContext);
        final ExpressionNode filter = model.getWhereClause();
        if (filter != null) {
            return new FilteredRecordCursorFactory(factory, functionParser.parseFunction(filter, factory.getMetadata(), executionContext));
        }
        return factory;
    }

    private RecordCursorFactory generateOrderBy(RecordCursorFactory recordCursorFactory, QueryModel model) throws SqlException {
        try {
            final CharSequenceIntHashMap orderBy = model.getOrderHash();
            final ObjList<CharSequence> columnNames = orderBy.keys();
            final int size = columnNames.size();

            if (size > 0) {

                final RecordMetadata metadata = recordCursorFactory.getMetadata();
                listColumnFilterA.clear();
                intHashSet.clear();

                // column index sign indicates direction
                // therefore 0 index is not allowed
                for (int i = 0; i < size; i++) {
                    final CharSequence column = columnNames.getQuick(i);
                    int index = metadata.getColumnIndexQuiet(column);

                    // check if column type is supported
                    if (metadata.getColumnType(index) == ColumnType.BINARY) {
                        // find position of offending column

                        ObjList<ExpressionNode> nodes = model.getOrderBy();
                        int position = 0;
                        for (int j = 0, y = nodes.size(); j < y; j++) {
                            if (Chars.equals(column, nodes.getQuick(i).token)) {
                                position = nodes.getQuick(i).position;
                                break;
                            }
                        }
                        throw SqlException.$(position, "unsupported column type: ").put(ColumnType.nameOf(metadata.getColumnType(index)));
                    }

                    // we also maintain unique set of column indexes for better performance
                    if (intHashSet.add(index)) {
                        if (orderBy.get(column) == QueryModel.ORDER_DIRECTION_DESCENDING) {
                            listColumnFilterA.add(-index - 1);
                        } else {
                            listColumnFilterA.add(index + 1);
                        }
                    }
                }

                // if first column index is the same as timestamp of underling record cursor factory
                // we could have two possibilities:
                // 1. if we only have one column to order by - the cursor would already be ordered
                //    by timestamp; we have nothing to do
                // 2. metadata of the new cursor will have timestamp

                RecordMetadata orderedMetadata;
                if (metadata.getTimestampIndex() == -1) {
                    orderedMetadata = GenericRecordMetadata.copyOfSansTimestamp(metadata);
                } else {
                    int index = metadata.getColumnIndexQuiet(columnNames.getQuick(0));
                    if (index == metadata.getTimestampIndex()) {

                        if (size == 1) {
                            return recordCursorFactory;
                        }

                        orderedMetadata = copyMetadata(metadata);

                    } else {
                        orderedMetadata = GenericRecordMetadata.copyOfSansTimestamp(metadata);
                    }
                }

                if (recordCursorFactory.isRandomAccessCursor()) {
                    return new SortedLightRecordCursorFactory(
                            configuration,
                            orderedMetadata,
                            recordCursorFactory,
                            recordComparatorCompiler.compile(metadata, listColumnFilterA)
                    );
                }

                // when base record cursor does not support random access
                // we have to copy entire record into ordered structure

                entityColumnFilter.of(orderedMetadata.getColumnCount());

                return new SortedRecordCursorFactory(
                        configuration,
                        orderedMetadata,
                        recordCursorFactory,
                        orderedMetadata,
                        RecordSinkFactory.getInstance(
                                asm,
                                orderedMetadata,
                                entityColumnFilter,
                                false
                        ),
                        recordComparatorCompiler.compile(metadata, listColumnFilterA)
                );
            }

            return recordCursorFactory;
        } catch (SqlException | CairoException e) {
            recordCursorFactory.close();
            throw e;
        }
    }

    private RecordCursorFactory generateQuery(QueryModel model, SqlExecutionContext executionContext, boolean processJoins) throws SqlException {
        RecordCursorFactory factory = generateQuery0(model, executionContext, processJoins);
        if (model.getUnionModel() != null) {
            return generateSetFactory(model, factory, executionContext);
        }
        return factory;
    }

    private RecordCursorFactory generateQuery0(QueryModel model, SqlExecutionContext executionContext, boolean processJoins) throws SqlException {
        return generateLimit(
                generateOrderBy(
                        generateSelect(
                                model,
                                executionContext,
                                processJoins
                        ),
                        model
                ),
                model,
                executionContext
        );
    }

    @NotNull
    private RecordCursorFactory generateSampleBy(QueryModel model, SqlExecutionContext executionContext, ExpressionNode sampleByNode) throws SqlException {
        final RecordCursorFactory factory = generateSubQuery(model, executionContext);
        // we require timestamp
        if (factory.getMetadata().getTimestampIndex() == -1) {
            throw SqlException.$(model.getSampleBy().position, "base query does not provide dedicated TIMESTAMP column");
        }
        final ObjList<ExpressionNode> sampleByFill = model.getSampleByFill();
        final TimestampSampler timestampSampler = TimestampSamplerFactory.getInstance(sampleByNode.token, sampleByNode.position);

        assert model.getNestedModel() != null;
        final int fillCount = sampleByFill.size();
        try {
            keyTypes.reset();
            valueTypes.reset();
            listColumnFilterA.clear();

            if (fillCount == 0 || fillCount == 1 && Chars.equalsLowerCaseAscii(sampleByFill.getQuick(0).token, "none")) {
                return new SampleByFillNoneRecordCursorFactory(
                        configuration,
                        factory,
                        timestampSampler,
                        model,
                        listColumnFilterA,
                        functionParser,
                        executionContext,
                        asm,
                        keyTypes,
                        valueTypes
                );
            }


            if (fillCount == 1 && Chars.equalsLowerCaseAscii(sampleByFill.getQuick(0).token, "prev")) {
                return new SampleByFillPrevRecordCursorFactory(
                        configuration,
                        factory,
                        timestampSampler,
                        model,
                        listColumnFilterA,
                        functionParser,
                        executionContext,
                        asm,
                        keyTypes,
                        valueTypes
                );
            }

            if (fillCount == 1 && Chars.equalsLowerCaseAscii(sampleByFill.getQuick(0).token, "null")) {
                return new SampleByFillNullRecordCursorFactory(
                        configuration,
                        factory,
                        timestampSampler,
                        model,
                        listColumnFilterA,
                        functionParser,
                        executionContext,
                        asm,
                        keyTypes,
                        valueTypes
                );
            }

            if (fillCount == 1 && Chars.equalsLowerCaseAscii(sampleByFill.getQuick(0).token, "linear")) {
                return new SampleByInterpolateRecordCursorFactory(
                        configuration,
                        factory,
                        timestampSampler,
                        model,
                        listColumnFilterA,
                        functionParser,
                        executionContext,
                        asm,
                        keyTypes,
                        valueTypes,
                        entityColumnFilter
                );
            }

            assert fillCount > 0;

            return new SampleByFillValueRecordCursorFactory(
                    configuration,
                    factory,
                    timestampSampler,
                    model,
                    listColumnFilterA,
                    functionParser,
                    executionContext,
                    asm,
                    sampleByFill,
                    keyTypes,
                    valueTypes
            );
        } catch (SqlException | CairoException e) {
            factory.close();
            throw e;
        }
    }

    private RecordCursorFactory generateSelect(
            QueryModel model,
            SqlExecutionContext executionContext,
            boolean processJoins
    ) throws SqlException {
        switch (model.getSelectModelType()) {
            case QueryModel.SELECT_MODEL_CHOOSE:
                return generateSelectChoose(model, executionContext);
            case QueryModel.SELECT_MODEL_GROUP_BY:
                return generateSelectGroupBy(model, executionContext);
            case QueryModel.SELECT_MODEL_VIRTUAL:
                return generateSelectVirtual(model, executionContext);
            case QueryModel.SELECT_MODEL_ANALYTIC:
                return generateSelectAnalytic(model, executionContext);
            case QueryModel.SELECT_MODEL_DISTINCT:
                return generateSelectDistinct(model, executionContext);
            default:
                if (model.getJoinModels().size() > 1 && processJoins) {
                    return generateJoins(model, executionContext);
                }
                return generateNoSelect(model, executionContext);
        }
    }

    private RecordCursorFactory generateSelectAnalytic(QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        return generateSubQuery(model, executionContext);
    }

    private RecordCursorFactory generateSelectChoose(QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        assert model.getNestedModel() != null;
        final RecordCursorFactory factory = generateSubQuery(model, executionContext);
        final RecordMetadata metadata = factory.getMetadata();
        final int selectColumnCount = model.getColumns().size();
        final ExpressionNode timestamp = model.getTimestamp();

        boolean entity;
        // the model is considered entity when it doesn't add any value to its nested model
        //
        if (timestamp == null && metadata.getColumnCount() == selectColumnCount) {
            entity = true;
            for (int i = 0; i < selectColumnCount; i++) {
                QueryColumn qc = model.getColumns().getQuick(i);
                if (
                        !Chars.equals(metadata.getColumnName(i), qc.getAst().token) ||
                                qc.getAlias() != null
                ) {
                    entity = false;
                    break;
                }
            }
        } else {
            entity = false;
        }

        if (entity) {
            return factory;
        }

        IntList columnCrossIndex = new IntList(selectColumnCount);
        GenericRecordMetadata selectMetadata = new GenericRecordMetadata();
        final int timestampIndex;
        if (timestamp == null) {
            timestampIndex = metadata.getTimestampIndex();
        } else {
            timestampIndex = metadata.getColumnIndexQuiet(timestamp.token);
            if (timestampIndex == -1) {
                throw SqlException.invalidColumn(timestamp.position, timestamp.token);
            }
        }
        for (int i = 0; i < selectColumnCount; i++) {
            final QueryColumn queryColumn = model.getColumns().getQuick(i);
            int index = metadata.getColumnIndexQuiet(queryColumn.getAst().token);
            assert index > -1 : "wtf? " + queryColumn.getAst().token;
            columnCrossIndex.add(index);

            selectMetadata.add(new TableColumnMetadata(
                    Chars.toString(queryColumn.getName()),
                    metadata.getColumnType(index),
                    metadata.isColumnIndexed(index),
                    metadata.getIndexValueBlockCapacity(index)
            ));

            if (index == timestampIndex) {
                selectMetadata.setTimestampIndex(i);
            }
        }

        return new SelectedRecordCursorFactory(selectMetadata, columnCrossIndex, factory);
    }

    private RecordCursorFactory generateSelectDistinct(QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        final RecordCursorFactory factory = generateSubQuery(model, executionContext);
        try {
            return new DistinctRecordCursorFactory(
                    configuration,
                    factory,
                    entityColumnFilter,
                    asm
            );

        } catch (CairoException e) {
            factory.close();
            throw e;
        }
    }

    private RecordCursorFactory generateSelectGroupBy(QueryModel model, SqlExecutionContext executionContext) throws SqlException {

        // fail fast if we cannot create timestamp sampler

        final ExpressionNode sampleByNode = model.getSampleBy();
        if (sampleByNode != null) {
            return generateSampleBy(model, executionContext, sampleByNode);
        }

        final RecordCursorFactory factory = generateSubQuery(model, executionContext);
        try {

            // generate special case plan for "select count() from somewhere"
            ObjList<QueryColumn> columns = model.getColumns();
            if (columns.size() == 1) {
                QueryColumn column = columns.getQuick(0);
                if (column.getAst().type == FUNCTION && Chars.equalsLowerCaseAscii(column.getAst().token, "count")) {
                    if (Chars.equalsLowerCaseAscii(column.getName(), "count")) {
                        return new CountRecordCursorFactory(CountRecordCursorFactory.DEFAULT_COUNT_METADATA, factory);
                    }

                    GenericRecordMetadata metadata = new GenericRecordMetadata();
                    metadata.add(new TableColumnMetadata(Chars.toString(column.getName()), ColumnType.LONG));
                    return new CountRecordCursorFactory(metadata, factory);
                }
            }

            keyTypes.reset();
            valueTypes.reset();
            listColumnFilterA.clear();

            return new GroupByRecordCursorFactory(
                    configuration,
                    factory,
                    model,
                    listColumnFilterA,
                    functionParser,
                    executionContext,
                    asm,
                    keyTypes,
                    valueTypes
            );

        } catch (CairoException e) {
            factory.close();
            throw e;
        }
    }

    private RecordCursorFactory generateSelectVirtual(QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        assert model.getNestedModel() != null;
        final RecordCursorFactory factory = generateSubQuery(model, executionContext);

        try {
            final int columnCount = model.getColumns().size();
            final RecordMetadata metadata = factory.getMetadata();
            final ObjList<Function> functions = new ObjList<>(columnCount);
            final GenericRecordMetadata virtualMetadata = new GenericRecordMetadata();

            // attempt to preserve timestamp on new data set
            CharSequence timestampColumn;
            final int timestampIndex = metadata.getTimestampIndex();
            if (timestampIndex > -1) {
                timestampColumn = metadata.getColumnName(timestampIndex);
            } else {
                timestampColumn = null;
            }

            IntList symbolTableCrossIndex = null;

            for (int i = 0; i < columnCount; i++) {
                final QueryColumn column = model.getColumns().getQuick(i);
                ExpressionNode node = column.getAst();
                if (timestampColumn != null && node.type == ExpressionNode.LITERAL && Chars.equals(timestampColumn, node.token)) {
                    virtualMetadata.setTimestampIndex(i);
                }

                final Function function = functionParser.parseFunction(
                        column.getAst(),
                        metadata,
                        executionContext
                );
                functions.add(function);


                virtualMetadata.add(new TableColumnMetadata(
                        Chars.toString(column.getAlias()),
                        function.getType()
                ));

                if (function instanceof SymbolColumn) {
                    if (symbolTableCrossIndex == null) {
                        symbolTableCrossIndex = new IntList(columnCount);
                    }
                    symbolTableCrossIndex.extendAndSet(i, ((SymbolColumn) function).getColumnIndex());
                }
            }

            return new VirtualRecordCursorFactory(virtualMetadata, functions, factory, symbolTableCrossIndex);
        } catch (SqlException | CairoException e) {
            factory.close();
            throw e;
        }
    }

    /**
     * Generates chain of parent factories each of which takes only two argument factories.
     * Parent factory will perform one of SET operations on its arguments, such as UNION, UNION ALL,
     * INTERSECT or EXCEPT
     *
     * @param model            incoming model is expected to have a chain of models via its QueryModel.getUnionModel() function
     * @param masterFactory    is compiled first argument
     * @param executionContext execution context for authorization and parallel execution purposes
     * @return factory that performs a SET operation
     * @throws SqlException when query contains syntax errors
     */
    private RecordCursorFactory generateSetFactory(
            QueryModel model,
            RecordCursorFactory masterFactory,
            SqlExecutionContext executionContext
    ) throws SqlException {
        RecordCursorFactory slaveFactory = generateQuery0(model.getUnionModel(), executionContext, true);
        if (model.getUnionModelType() == QueryModel.UNION_MODEL_DISTINCT) {
            return generateUnionFactory(model, masterFactory, executionContext, slaveFactory);
        } else if (model.getUnionModelType() == QueryModel.UNION_MODEL_ALL) {
            return generateUnionAllFactory(model, masterFactory, executionContext, slaveFactory);
        }
        assert false;
        return null;
    }

    private RecordCursorFactory generateSubQuery(QueryModel model, SqlExecutionContext executionContext) throws SqlException {
        assert model.getNestedModel() != null;
        return generateQuery(model.getNestedModel(), executionContext, true);
    }

    @SuppressWarnings("ConstantConditions")
    private RecordCursorFactory generateTableQuery(
            QueryModel model,
            SqlExecutionContext executionContext
    ) throws SqlException {
        final ObjList<ExpressionNode> latestBy = model.getLatestBy();
        final ExpressionNode whereClause = model.getWhereClause();

        try (TableReader reader = engine.getReader(
                executionContext.getCairoSecurityContext(),
                model.getTableName().token,
                model.getTableVersion())
        ) {
            final GenericRecordMetadata metadata = copyMetadata(reader.getMetadata());
            final int timestampIndex;

            final ExpressionNode timestamp = model.getTimestamp();
            if (timestamp != null) {
                timestampIndex = metadata.getColumnIndexQuiet(timestamp.token);
                metadata.setTimestampIndex(timestampIndex);
            } else {
                timestampIndex = -1;
            }

            listColumnFilterA.clear();
            final int latestByColumnCount = latestBy.size();

            if (latestBy.size() > 0) {
                // validate latest by against current reader
                // first check if column is valid
                for (int i = 0; i < latestByColumnCount; i++) {
                    final int index = metadata.getColumnIndexQuiet(latestBy.getQuick(i).token);
                    if (index == -1) {
                        throw SqlException.invalidColumn(latestBy.getQuick(i).position, latestBy.getQuick(i).token);
                    }

                    // we are reusing collections which leads to confusing naming for this method
                    // keyTypes are types of columns we collect 'latest by' for
                    keyTypes.add(metadata.getColumnType(index));
                    // columnFilterA are indexes of columns we collect 'latest by' for
                    listColumnFilterA.add(index);
                }
            }

            final String tableName = Chars.toString(model.getTableName().token);

            if (whereClause != null) {

                final IntrinsicModel intrinsicModel = filterAnalyser.extract(model, whereClause, metadata, latestByColumnCount > 0 ? latestBy.getQuick(0).token : null, timestampIndex);

                if (intrinsicModel.intrinsicValue == IntrinsicModel.FALSE) {
                    return new EmptyTableRecordCursorFactory(metadata);
                }

                Function filter;

                if (intrinsicModel.filter != null) {
                    filter = functionParser.parseFunction(intrinsicModel.filter, metadata, executionContext);

                    if (filter.getType() != ColumnType.BOOLEAN) {
                        throw SqlException.$(intrinsicModel.filter.position, "boolean expression expected");
                    }

                    if (filter.isConstant()) {
                        // can pass null to constant function
                        if (filter.getBool(null)) {
                            // filter is constant "true", do not evaluate for every row
                            filter = null;
                        } else {
                            return new EmptyTableRecordCursorFactory(metadata);
                        }
                    }
                } else {
                    filter = null;
                }

                DataFrameCursorFactory dfcFactory;

                if (latestByColumnCount > 0) {
                    return generateLatestByQuery(
                            model,
                            reader,
                            metadata,
                            tableName,
                            intrinsicModel,
                            filter,
                            executionContext
                    );
                }


                // below code block generates index-based filter

                if (intrinsicModel.intervals != null) {
                    dfcFactory = new IntervalFwdDataFrameCursorFactory(engine, tableName, model.getTableVersion(), intrinsicModel.intervals);
                } else {
                    dfcFactory = new FullFwdDataFrameCursorFactory(engine, tableName, model.getTableVersion());
                }

                if (intrinsicModel.keyColumn != null) {
                    // existence of column would have been already validated
                    final int keyColumnIndex = reader.getMetadata().getColumnIndexQuiet(intrinsicModel.keyColumn);
                    final int nKeyValues = intrinsicModel.keyValues.size();

                    if (intrinsicModel.keySubQuery != null) {
                        final RecordCursorFactory rcf = generate(intrinsicModel.keySubQuery, executionContext);
                        final int firstColumnType = validateSubQueryColumnAndGetType(intrinsicModel, rcf.getMetadata());

                        return new FilterOnSubQueryRecordCursorFactory(
                                metadata,
                                dfcFactory,
                                rcf,
                                keyColumnIndex,
                                filter,
                                firstColumnType
                        );
                    }
                    assert nKeyValues > 0;

                    if (nKeyValues == 1) {
                        final RowCursorFactory rcf;
                        final CharSequence symbol = intrinsicModel.keyValues.get(0);
                        final int symbolKey = reader.getSymbolMapReader(keyColumnIndex).getQuick(symbol);
                        if (symbolKey == SymbolTable.VALUE_NOT_FOUND) {
                            if (filter == null) {
                                rcf = new DeferredSymbolIndexRowCursorFactory(keyColumnIndex, Chars.toString(symbol), true);
                            } else {
                                rcf = new DeferredSymbolIndexFilteredRowCursorFactory(keyColumnIndex, Chars.toString(symbol), filter, true);
                            }
                        } else {
                            if (filter == null) {
                                rcf = new SymbolIndexRowCursorFactory(keyColumnIndex, symbolKey, true);
                            } else {
                                rcf = new SymbolIndexFilteredRowCursorFactory(keyColumnIndex, symbolKey, filter, true);
                            }
                        }
                        return new DataFrameRecordCursorFactory(metadata, dfcFactory, rcf, filter);
                    }

                    return new FilterOnValuesRecordCursorFactory(
                            metadata,
                            dfcFactory,
                            intrinsicModel.keyValues,
                            keyColumnIndex,
                            reader,
                            filter
                    );
                }

                if (filter != null) {
                    // filter lifecycle is managed by top level
                    return new FilteredRecordCursorFactory(new DataFrameRecordCursorFactory(metadata, dfcFactory, new DataFrameRowCursorFactory(), null), filter);
                }
                return new DataFrameRecordCursorFactory(metadata, dfcFactory, new DataFrameRowCursorFactory(), filter);
            }

            // no where clause
            if (latestByColumnCount == 0) {
                return new TableReaderRecordCursorFactory(copyMetadata(metadata), engine, tableName, model.getTableVersion());
            }

            if (latestByColumnCount == 1 && metadata.isColumnIndexed(listColumnFilterA.getQuick(0))) {
                return new LatestByAllIndexedFilteredRecordCursorFactory(
                        configuration,
                        copyMetadata(metadata),
                        new FullBwdDataFrameCursorFactory(engine, tableName, model.getTableVersion()),
                        listColumnFilterA.getQuick(0),
                        null
                );
            }

            return new LatestByAllFilteredRecordCursorFactory(
                    copyMetadata(metadata),
                    configuration,
                    new FullBwdDataFrameCursorFactory(engine, tableName, model.getTableVersion()),
                    RecordSinkFactory.getInstance(asm, metadata, listColumnFilterA, false),
                    keyTypes,
                    null
            );
        }
    }

    private RecordCursorFactory generateUnionAllFactory(QueryModel model, RecordCursorFactory masterFactory, SqlExecutionContext executionContext, RecordCursorFactory slaveFactory) throws SqlException {
        validateJoinColumnTypes(model, masterFactory, slaveFactory);
        final RecordCursorFactory unionAllFactory = new UnionAllRecordCursorFactory(masterFactory, slaveFactory);

        if (model.getUnionModel().getUnionModel() != null) {
            return generateSetFactory(model.getUnionModel(), unionAllFactory, executionContext);
        }
        return unionAllFactory;
    }

    private RecordCursorFactory generateUnionFactory(QueryModel model, RecordCursorFactory masterFactory, SqlExecutionContext executionContext, RecordCursorFactory slaveFactory) throws SqlException {
        validateJoinColumnTypes(model, masterFactory, slaveFactory);
        entityColumnFilter.of(masterFactory.getMetadata().getColumnCount());
        final RecordSink recordSink = RecordSinkFactory.getInstance(
                asm,
                masterFactory.getMetadata(),
                entityColumnFilter,
                true
        );

        valueTypes.reset();

        RecordCursorFactory unionFactory = new UnionRecordCursorFactory(
                configuration,
                masterFactory,
                slaveFactory,
                recordSink,
                valueTypes
        );

        if (model.getUnionModel().getUnionModel() != null) {
            return generateSetFactory(model.getUnionModel(), unionFactory, executionContext);
        }
        return unionFactory;
    }

    private void lookupColumnIndexes(
            ListColumnFilter filter,
            ObjList<ExpressionNode> columnNames,
            RecordMetadata masterMetadata
    ) {
        filter.clear();
        for (int i = 0, n = columnNames.size(); i < n; i++) {
            filter.add(masterMetadata.getColumnIndex(columnNames.getQuick(i).token));
        }
    }

    private void lookupColumnIndexesUsingVanillaNames(
            ListColumnFilter filter,
            ObjList<CharSequence> columnNames,
            RecordMetadata metadata
    ) {
        filter.clear();
        for (int i = 0, n = columnNames.size(); i < n; i++) {
            filter.add(metadata.getColumnIndex(columnNames.getQuick(i)));
        }
    }

    private void processJoinContext(boolean vanillaMaster, JoinContext jc, RecordMetadata masterMetadata, RecordMetadata slaveMetadata) throws SqlException {
        lookupColumnIndexesUsingVanillaNames(listColumnFilterA, jc.aNames, slaveMetadata);
        if (vanillaMaster) {
            lookupColumnIndexesUsingVanillaNames(listColumnFilterB, jc.bNames, masterMetadata);
        } else {
            lookupColumnIndexes(listColumnFilterB, jc.bNodes, masterMetadata);
        }

        // compare types and populate keyTypes
        keyTypes.reset();
        for (int k = 0, m = listColumnFilterA.getColumnCount(); k < m; k++) {
            int columnType = masterMetadata.getColumnType(listColumnFilterB.getColumnIndex(k));
            if (columnType != slaveMetadata.getColumnType(listColumnFilterA.getColumnIndex(k))) {
                // index in column filter and join context is the same
                throw SqlException.$(jc.aNodes.getQuick(k).position, "join column type mismatch");
            }
            keyTypes.add(columnType == ColumnType.SYMBOL ? ColumnType.STRING : columnType);
        }
    }

    void setFullFatJoins(boolean fullFatJoins) {
        this.fullFatJoins = fullFatJoins;
    }

    private void validateJoinColumnTypes(QueryModel model, RecordCursorFactory masterFactory, RecordCursorFactory slaveFactory) throws SqlException {
        final RecordMetadata metadata = masterFactory.getMetadata();
        final RecordMetadata slaveMetadata = slaveFactory.getMetadata();
        final int columnCount = metadata.getColumnCount();

        for (int i = 0; i < columnCount; i++) {
            if (metadata.getColumnType(i) != slaveMetadata.getColumnType(i)) {
                throw SqlException
                        .$(model.getUnionModel().getModelPosition(), "column type mismatch [index=").put(i)
                        .put(", A=").put(ColumnType.nameOf(metadata.getColumnType(i)))
                        .put(", B=").put(ColumnType.nameOf(slaveMetadata.getColumnType(i)))
                        .put(']');
            }
        }
    }

    private int validateSubQueryColumnAndGetType(IntrinsicModel intrinsicModel, RecordMetadata metadata) throws SqlException {
        final int firstColumnType = metadata.getColumnType(0);
        if (firstColumnType != ColumnType.STRING && firstColumnType != ColumnType.SYMBOL) {
            assert intrinsicModel.keySubQuery.getColumns() != null;
            assert intrinsicModel.keySubQuery.getColumns().size() > 0;

            throw SqlException
                    .position(intrinsicModel.keySubQuery.getColumns().getQuick(0).getAst().position)
                    .put("unsupported column type: ")
                    .put(metadata.getColumnName(0))
                    .put(": ")
                    .put(ColumnType.nameOf(firstColumnType));
        }
        return firstColumnType;
    }

    static {
        limitTypes.add(ColumnType.LONG);
        limitTypes.add(ColumnType.BYTE);
        limitTypes.add(ColumnType.SHORT);
        limitTypes.add(ColumnType.INT);
    }

}
