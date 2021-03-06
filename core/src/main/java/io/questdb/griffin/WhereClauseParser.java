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

import io.questdb.cairo.ColumnType;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.griffin.model.AliasTranslator;
import io.questdb.griffin.model.ExpressionNode;
import io.questdb.griffin.model.IntrinsicModel;
import io.questdb.std.*;
import io.questdb.std.microtime.DateFormatUtils;
import io.questdb.std.str.FlyweightCharSequence;

import java.util.ArrayDeque;

final class WhereClauseParser {

    private static final int INTRINCIC_OP_IN = 1;
    private static final int INTRINCIC_OP_GREATER = 2;
    private static final int INTRINCIC_OP_GREATER_EQ = 3;
    private static final int INTRINCIC_OP_LESS = 4;
    private static final int INTRINCIC_OP_LESS_EQ = 5;
    private static final int INTRINCIC_OP_EQUAL = 6;
    private static final int INTRINCIC_OP_NOT_EQ = 7;
    private static final int INTRINCIC_OP_NOT = 8;
    private static final CharSequenceIntHashMap intrinsicOps = new CharSequenceIntHashMap();

    static {
        intrinsicOps.put("in", INTRINCIC_OP_IN);
        intrinsicOps.put(">", INTRINCIC_OP_GREATER);
        intrinsicOps.put(">=", INTRINCIC_OP_GREATER_EQ);
        intrinsicOps.put("<", INTRINCIC_OP_LESS);
        intrinsicOps.put("<=", INTRINCIC_OP_LESS_EQ);
        intrinsicOps.put("=", INTRINCIC_OP_EQUAL);
        intrinsicOps.put("!=", INTRINCIC_OP_NOT_EQ);
        intrinsicOps.put("not", INTRINCIC_OP_NOT);
    }

    private final ArrayDeque<ExpressionNode> stack = new ArrayDeque<>();
    private final ObjList<ExpressionNode> keyNodes = new ObjList<>();
    private final ObjList<ExpressionNode> keyExclNodes = new ObjList<>();
    private final ObjectPool<IntrinsicModel> models = new ObjectPool<>(IntrinsicModel.FACTORY, 8);
    private final CharSequenceHashSet tempKeys = new CharSequenceHashSet();
    private final IntList tempPos = new IntList();
    private final CharSequenceHashSet tempK = new CharSequenceHashSet();
    private final IntList tempP = new IntList();
    private final ObjectPool<FlyweightCharSequence> csPool = new ObjectPool<>(FlyweightCharSequence.FACTORY, 64);
    private CharSequence timestamp;
    private CharSequence preferredKeyColumn;

    private static void checkNodeValid(ExpressionNode node) throws SqlException {
        if (node.lhs == null || node.rhs == null) {
            throw SqlException.$(node.position, "Argument expected");
        }
    }

    private boolean analyzeEquals(AliasTranslator translator, IntrinsicModel model, ExpressionNode node, RecordMetadata m) throws SqlException {
        checkNodeValid(node);
        return analyzeEquals0(translator, model, node, node.lhs, node.rhs, m) || analyzeEquals0(translator, model, node, node.rhs, node.lhs, m);
    }

    private boolean analyzeEquals0(AliasTranslator translator, IntrinsicModel model, ExpressionNode node, ExpressionNode a, ExpressionNode b, RecordMetadata m) throws SqlException {
        if (Chars.equals(a.token, b.token)) {
            node.intrinsicValue = IntrinsicModel.TRUE;
            return true;
        }

        if (a.type == ExpressionNode.LITERAL && b.type == ExpressionNode.CONSTANT) {
            if (isTimestamp(a)) {
                model.intersectIntervals(b.token, 1, b.token.length() - 1, b.position);
                node.intrinsicValue = IntrinsicModel.TRUE;
                return true;
            } else {
                CharSequence column = translator.translateAlias(a.token);
                int index = m.getColumnIndexQuiet(column);
                if (index == -1) {
                    throw SqlException.invalidColumn(a.position, a.token);
                }

                switch (m.getColumnType(index)) {
                    case ColumnType.SYMBOL:
                    case ColumnType.STRING:
                    case ColumnType.LONG:
                    case ColumnType.INT:
                        final boolean preferred = Chars.equalsIgnoreCaseNc(preferredKeyColumn, column);
                        final boolean indexed = m.isColumnIndexed(index);
                        if (preferred || (indexed && preferredKeyColumn == null)) {
                            CharSequence value = Chars.equalsLowerCaseAscii(b.token, "null") ? null : unquote(b.token);
                            if (Chars.equalsIgnoreCaseNc(model.keyColumn, column)) {
                                // compute overlap of values
                                // if values do overlap, keep only our value
                                // otherwise invalidate entire model
                                if (model.keyValues.contains(value)) {
                                    // when we have "x in ('a,'b') and x = 'a')" the x='b' can never happen
                                    // so we have to clear all other key values
                                    if (model.keyValues.size() > 1) {
                                        model.keyValues.clear();
                                        model.keyValuePositions.clear();
                                        model.keyValues.add(value);
                                        model.keyValuePositions.add(b.position);
                                    }
                                } else {
                                    model.intrinsicValue = IntrinsicModel.FALSE;
                                    return false;
                                }
                            } else {
                                model.keyColumn = column;
                                model.keyValues.clear();
                                model.keyValuePositions.clear();
                                model.keyValues.add(value);
                                model.keyValuePositions.add(b.position);
                                for (int n = 0, k = keyNodes.size(); n < k; n++) {
                                    keyNodes.getQuick(n).intrinsicValue = IntrinsicModel.UNDEFINED;
                                }
                                keyNodes.clear();
                            }

                            keyNodes.add(node);
                            node.intrinsicValue = IntrinsicModel.TRUE;
                            return true;
                        }
                        //fall through
                    default:
                        return false;
                }

            }

        }
        return false;
    }

    private boolean analyzeGreater(IntrinsicModel model, ExpressionNode node, int increment) throws SqlException {
        checkNodeValid(node);

        if (Chars.equals(node.lhs.token, node.rhs.token)) {
            model.intrinsicValue = IntrinsicModel.FALSE;
            return false;
        }

        if (timestamp == null) {
            return false;
        }

        if (node.lhs.type == ExpressionNode.LITERAL && Chars.equals(node.lhs.token, timestamp)) {

            if (node.rhs.type != ExpressionNode.CONSTANT) {
                return false;
            }

            try {
                model.intersectIntervals(DateFormatUtils.tryParse(node.rhs.token, 1, node.rhs.token.length() - 1) + increment, Long.MAX_VALUE);
                node.intrinsicValue = IntrinsicModel.TRUE;
                return true;
            } catch (NumericException e) {
                throw SqlException.invalidDate(node.rhs.position);
            }
        }

        if (node.rhs.type == ExpressionNode.LITERAL && Chars.equals(node.rhs.token, timestamp)) {

            if (node.lhs.type != ExpressionNode.CONSTANT) {
                return false;
            }

            try {
                model.intersectIntervals(Long.MIN_VALUE, DateFormatUtils.tryParse(node.lhs.token, 1, node.lhs.token.length() - 1) - increment);
                return true;
            } catch (NumericException e) {
                throw SqlException.invalidDate(node.lhs.position);
            }
        }
        return false;
    }

    private boolean analyzeIn(AliasTranslator translator, IntrinsicModel model, ExpressionNode node, RecordMetadata metadata) throws SqlException {

        if (node.paramCount < 2) {
            throw SqlException.$(node.position, "Too few arguments for 'in'");
        }

        ExpressionNode col = node.paramCount < 3 ? node.lhs : node.args.getLast();

        if (col.type != ExpressionNode.LITERAL) {
            return false;
        }

        CharSequence column = translator.translateAlias(col.token);

        if (metadata.getColumnIndexQuiet(column) == -1) {
            throw SqlException.invalidColumn(col.position, col.token);
        }
        return analyzeInInterval(model, col, node)
                || analyzeListOfValues(model, column, metadata, node)
                || analyzeInLambda(model, column, metadata, node);
    }

    private boolean analyzeInInterval(IntrinsicModel model, ExpressionNode col, ExpressionNode in) throws SqlException {
        if (!isTimestamp(col)) {
            return false;
        }

        if (in.paramCount > 3) {
            throw SqlException.$(in.args.getQuick(0).position, "Too many args");
        }

        if (in.paramCount < 3) {
            throw SqlException.$(in.position, "Too few args");
        }

        ExpressionNode lo = in.args.getQuick(1);
        ExpressionNode hi = in.args.getQuick(0);

        if (lo.type == ExpressionNode.CONSTANT && hi.type == ExpressionNode.CONSTANT) {
            long loMillis;
            long hiMillis;

            try {
                loMillis = DateFormatUtils.tryParse(lo.token, 1, lo.token.length() - 1);
            } catch (NumericException ignore) {
                throw SqlException.invalidDate(lo.position);
            }

            try {
                hiMillis = DateFormatUtils.tryParse(hi.token, 1, hi.token.length() - 1);
            } catch (NumericException ignore) {
                throw SqlException.invalidDate(hi.position);
            }

            model.intersectIntervals(loMillis, hiMillis);
            in.intrinsicValue = IntrinsicModel.TRUE;
            return true;
        }
        return false;
    }

    private boolean analyzeInLambda(IntrinsicModel model, CharSequence columnName, RecordMetadata meta, ExpressionNode node) throws SqlException {
//        RecordColumnMetadata colMeta = meta.getColumn(columnName);
        int columnIndex = meta.getColumnIndex(columnName);
        boolean preferred = Chars.equalsIgnoreCaseNc(preferredKeyColumn, columnName);

        if (preferred || (preferredKeyColumn == null && meta.isColumnIndexed(columnIndex))) {
            if (preferredKeyColumn != null && !Chars.equalsIgnoreCase(columnName, preferredKeyColumn)) {
                return false;
            }

            if (node.rhs == null || node.rhs.type != ExpressionNode.QUERY) {
                return false;
            }

            // check if we already have indexed column and it is of worse selectivity
            if (model.keyColumn != null
                    && (!Chars.equalsIgnoreCase(model.keyColumn, columnName))
                    && meta.getIndexValueBlockCapacity(columnIndex) <= meta.getIndexValueBlockCapacity(model.keyColumn)) {
                return false;
            }

            if ((Chars.equalsIgnoreCaseNc(model.keyColumn, columnName) && model.keySubQuery != null) || node.paramCount > 2) {
                throw SqlException.$(node.position, "Multiple lambda expressions not supported");
            }

            model.keyValues.clear();
            model.keyValuePositions.clear();
            model.keyValuePositions.add(node.position);
            model.keySubQuery = node.rhs.queryModel;

            // revert previously processed nodes
            for (int n = 0, k = keyNodes.size(); n < k; n++) {
                keyNodes.getQuick(n).intrinsicValue = IntrinsicModel.UNDEFINED;
            }
            keyNodes.clear();
            model.keyColumn = columnName;
            keyNodes.add(node);
            node.intrinsicValue = IntrinsicModel.TRUE;
            return true;
        }
        return false;
    }

    private boolean analyzeLess(IntrinsicModel model, ExpressionNode node, int inc) throws SqlException {

        checkNodeValid(node);

        if (Chars.equals(node.lhs.token, node.rhs.token)) {
            model.intrinsicValue = IntrinsicModel.FALSE;
            return false;
        }

        if (timestamp == null) {
            return false;
        }

        if (node.lhs.type == ExpressionNode.LITERAL && Chars.equals(node.lhs.token, timestamp)) {
            try {

                if (node.rhs.type != ExpressionNode.CONSTANT) {
                    return false;
                }

                long hi = DateFormatUtils.tryParse(node.rhs.token, 1, node.rhs.token.length() - 1) - inc;
                model.intersectIntervals(Long.MIN_VALUE, hi);
                node.intrinsicValue = IntrinsicModel.TRUE;
                return true;
            } catch (NumericException e) {
                throw SqlException.invalidDate(node.rhs.position);
            }
        }

        if (node.rhs.type == ExpressionNode.LITERAL && Chars.equals(node.rhs.token, timestamp)) {
            try {
                if (node.lhs.type != ExpressionNode.CONSTANT) {
                    return false;
                }

                long lo = DateFormatUtils.tryParse(node.lhs.token, 1, node.lhs.token.length() - 1) + inc;
                model.intersectIntervals(lo, Long.MAX_VALUE);
                node.intrinsicValue = IntrinsicModel.TRUE;
                return true;
            } catch (NumericException e) {
                throw SqlException.invalidDate(node.lhs.position);
            }
        }
        return false;
    }

    private boolean analyzeListOfValues(IntrinsicModel model, CharSequence columnName, RecordMetadata meta, ExpressionNode node) {
        final int columnIndex = meta.getColumnIndex(columnName);
        boolean newColumn = true;
        boolean preferred = Chars.equalsIgnoreCaseNc(preferredKeyColumn, columnName);

        if (preferred || (preferredKeyColumn == null && meta.isColumnIndexed(columnIndex))) {

            // check if we already have indexed column and it is of worse selectivity
            // "preferred" is an unfortunate name, this column is from "latest by" clause, I should name it better
            //
            if (model.keyColumn != null
                    && (newColumn = !Chars.equals(model.keyColumn, columnName))
                    && meta.getIndexValueBlockCapacity(columnIndex) <= meta.getIndexValueBlockCapacity(model.keyColumn)) {
                return false;
            }


            int i = node.paramCount - 1;
            tempKeys.clear();
            tempPos.clear();

            // collect and analyze values of indexed field
            // if any of values is not an indexed constant - bail out
            if (i == 1) {
                if (node.rhs == null || node.rhs.type != ExpressionNode.CONSTANT) {
                    return false;
                }
                if (tempKeys.add(unquote(node.rhs.token))) {
                    tempPos.add(node.position);
                }
            } else {
                for (i--; i > -1; i--) {
                    ExpressionNode c = node.args.getQuick(i);
                    if (c.type != ExpressionNode.CONSTANT) {
                        return false;
                    }

                    if (Chars.equalsLowerCaseAscii(c.token, "null")) {
                        if (tempKeys.add(null)) {
                            tempPos.add(c.position);
                        }
                    } else {
                        if (tempKeys.add(unquote(c.token))) {
                            tempPos.add(c.position);
                        }
                    }
                }
            }

            // clear values if this is new column
            // and reset intrinsic values on nodes associated with old column
            if (newColumn) {
                model.keyValues.clear();
                model.keyValuePositions.clear();
                model.keyValues.addAll(tempKeys);
                model.keyValuePositions.addAll(tempPos);
                for (int n = 0, k = keyNodes.size(); n < k; n++) {
                    keyNodes.getQuick(n).intrinsicValue = IntrinsicModel.UNDEFINED;
                }
                keyNodes.clear();
                model.keyColumn = columnName;
                keyNodes.add(node);
                node.intrinsicValue = IntrinsicModel.TRUE;
                return true;

            } else if (model.keySubQuery == null) {
                // calculate overlap of values
                replaceAllWithOverlap(model);

                keyNodes.add(node);
                node.intrinsicValue = IntrinsicModel.TRUE;
                return true;
            }
        }
        return false;
    }

    private boolean analyzeNotEquals(AliasTranslator translator, IntrinsicModel model, ExpressionNode node, RecordMetadata m) throws SqlException {
        checkNodeValid(node);
        return analyzeNotEquals0(translator, model, node, node.lhs, node.rhs, m)
                || analyzeNotEquals0(translator, model, node, node.rhs, node.lhs, m);
    }

    private boolean analyzeNotEquals0(AliasTranslator translator, IntrinsicModel model, ExpressionNode node, ExpressionNode a, ExpressionNode b, RecordMetadata m) throws SqlException {

        if (Chars.equals(a.token, b.token)) {
            model.intrinsicValue = IntrinsicModel.FALSE;
            return true;
        }

        if (a.type == ExpressionNode.LITERAL && b.type == ExpressionNode.CONSTANT) {
            if (isTimestamp(a)) {
                model.subtractIntervals(b.token, 1, b.token.length() - 1, b.position);
                node.intrinsicValue = IntrinsicModel.TRUE;
                return true;
            } else {
                CharSequence column = translator.translateAlias(a.token);
                int index = m.getColumnIndexQuiet(column);
                if (index == -1) {
                    throw SqlException.invalidColumn(a.position, a.token);
                }

                switch (m.getColumnType(index)) {
                    case ColumnType.SYMBOL:
                    case ColumnType.STRING:
                    case ColumnType.LONG:
                    case ColumnType.INT:
                        if (m.isColumnIndexed(index)) {

                            // check if we are limited by preferred column
                            if (preferredKeyColumn != null && !Chars.equals(preferredKeyColumn, column)) {
                                return false;
                            }

                            keyExclNodes.add(node);
                            return false;
                        }
                        break;
                    default:
                        break;
                }
            }

        }
        return false;
    }

    private boolean analyzeNotIn(AliasTranslator translator, IntrinsicModel model, ExpressionNode notNode, RecordMetadata m) throws SqlException {

        ExpressionNode node = notNode.rhs;

        if (node.paramCount < 2) {
            throw SqlException.$(node.position, "Too few arguments for 'in'");
        }

        ExpressionNode col = node.paramCount < 3 ? node.lhs : node.args.getLast();

        if (col.type != ExpressionNode.LITERAL) {
            throw SqlException.$(col.position, "Column name expected");
        }

        CharSequence column = translator.translateAlias(col.token);

        if (m.getColumnIndexQuiet(column) == -1) {
            throw SqlException.invalidColumn(col.position, col.token);
        }

        boolean ok = analyzeNotInInterval(model, col, node);
        if (ok) {
            notNode.intrinsicValue = IntrinsicModel.TRUE;
        } else {
            analyzeNotListOfValues(column, m, notNode);
        }

        return ok;
    }

    private boolean analyzeNotInInterval(IntrinsicModel model, ExpressionNode col, ExpressionNode in) throws SqlException {
        if (!isTimestamp(col)) {
            return false;
        }

        if (in.paramCount > 3) {
            throw SqlException.$(in.args.getQuick(0).position, "Too many args");
        }

        if (in.paramCount < 3) {
            throw SqlException.$(in.position, "Too few args");
        }

        ExpressionNode lo = in.args.getQuick(1);
        ExpressionNode hi = in.args.getQuick(0);

        if (lo.type == ExpressionNode.CONSTANT && hi.type == ExpressionNode.CONSTANT) {
            long loMillis;
            long hiMillis;

            try {
                loMillis = DateFormatUtils.tryParse(lo.token, 1, lo.token.length() - 1);
            } catch (NumericException ignore) {
                throw SqlException.invalidDate(lo.position);
            }

            try {
                hiMillis = DateFormatUtils.tryParse(hi.token, 1, hi.token.length() - 1);
            } catch (NumericException ignore) {
                throw SqlException.invalidDate(hi.position);
            }

            model.subtractIntervals(loMillis, hiMillis);
            in.intrinsicValue = IntrinsicModel.TRUE;
            return true;
        }
        return false;
    }

    private void analyzeNotListOfValues(CharSequence columnName, RecordMetadata m, ExpressionNode notNode) {
        final int columnIndex = m.getColumnIndex(columnName);

        switch (m.getColumnType(columnIndex)) {
            case ColumnType.SYMBOL:
            case ColumnType.STRING:
            case ColumnType.LONG:
            case ColumnType.INT:
                if (m.isColumnIndexed(columnIndex) && (preferredKeyColumn == null || Chars.equals(preferredKeyColumn, columnName))) {
                    keyExclNodes.add(notNode);
                }
                break;
            default:
                break;
        }
    }

    private void applyKeyExclusions(AliasTranslator translator, IntrinsicModel model) {
        if (model.keyColumn != null && keyExclNodes.size() > 0) {
            OUT:
            for (int i = 0, n = keyExclNodes.size(); i < n; i++) {
                ExpressionNode parent = keyExclNodes.getQuick(i);


                ExpressionNode node = Chars.equalsLowerCaseAscii(parent.token, "not") ? parent.rhs : parent;
                // this could either be '=' or 'in'

                if (node.paramCount == 2) {
                    ExpressionNode col;
                    ExpressionNode val;

                    if (node.lhs.type == ExpressionNode.LITERAL) {
                        col = node.lhs;
                        val = node.rhs;
                    } else {
                        col = node.rhs;
                        val = node.lhs;
                    }

                    final CharSequence column = translator.translateAlias(col.token);
                    if (Chars.equals(column, model.keyColumn)) {
                        model.excludeValue(val);
                        parent.intrinsicValue = IntrinsicModel.TRUE;
                        if (model.intrinsicValue == IntrinsicModel.FALSE) {
                            break;
                        }
                    }
                }

                if (node.paramCount > 2) {
                    ExpressionNode col = node.args.getQuick(node.paramCount - 1);
                    final CharSequence column = translator.translateAlias(col.token);
                    if (Chars.equals(column, model.keyColumn)) {
                        for (int j = node.paramCount - 2; j > -1; j--) {
                            ExpressionNode val = node.args.getQuick(j);
                            model.excludeValue(val);
                            if (model.intrinsicValue == IntrinsicModel.FALSE) {
                                break OUT;
                            }
                        }
                        parent.intrinsicValue = IntrinsicModel.TRUE;
                    }

                }
            }
        }
        keyExclNodes.clear();
    }

    private ExpressionNode collapseIntrinsicNodes(ExpressionNode node) {
        if (node == null || node.intrinsicValue == IntrinsicModel.TRUE) {
            return null;
        }
        node.lhs = collapseIntrinsicNodes(collapseNulls0(node.lhs));
        node.rhs = collapseIntrinsicNodes(collapseNulls0(node.rhs));
        return collapseNulls0(node);
    }

    private ExpressionNode collapseNulls0(ExpressionNode node) {
        if (node == null || node.intrinsicValue == IntrinsicModel.TRUE) {
            return null;
        }
        if (node.queryModel == null && Chars.equalsLowerCaseAscii(node.token, "and")) {
            if (node.lhs == null || node.lhs.intrinsicValue == IntrinsicModel.TRUE) {
                return node.rhs;
            }
            if (node.rhs == null || node.rhs.intrinsicValue == IntrinsicModel.TRUE) {
                return node.lhs;
            }
        }
        return node;
    }

    IntrinsicModel extract(AliasTranslator translator, ExpressionNode node, RecordMetadata m, CharSequence preferredKeyColumn, int timestampIndex) throws SqlException {
        reset();
        this.timestamp = timestampIndex < 0 ? null : m.getColumnName(timestampIndex);
        this.preferredKeyColumn = preferredKeyColumn;

        IntrinsicModel model = models.next();

        // pre-order iterative tree traversal
        // see: http://en.wikipedia.org/wiki/Tree_traversal

        if (removeAndIntrinsics(translator, model, node, m)) {
            return model;
        }
        ExpressionNode root = node;

        while (!stack.isEmpty() || node != null) {
            if (node != null) {
                if (Chars.equalsLowerCaseAscii(node.token, "and")) {
                    if (!removeAndIntrinsics(translator, model, node.rhs, m)) {
                        stack.push(node.rhs);
                    }
                    node = removeAndIntrinsics(translator, model, node.lhs, m) ? null : node.lhs;
                } else {
                    node = stack.poll();
                }
            } else {
                node = stack.poll();
            }
        }
        applyKeyExclusions(translator, model);
        model.filter = collapseIntrinsicNodes(root);
        return model;
    }

    private boolean isTimestamp(ExpressionNode n) {
        return timestamp != null && Chars.equals(timestamp, n.token);
    }

    private boolean removeAndIntrinsics(AliasTranslator translator, IntrinsicModel model, ExpressionNode node, RecordMetadata m) throws SqlException {
        switch (intrinsicOps.get(node.token)) {
            case INTRINCIC_OP_IN:
                return analyzeIn(translator, model, node, m);
            case INTRINCIC_OP_GREATER:
                return analyzeGreater(model, node, 1);
            case INTRINCIC_OP_GREATER_EQ:
                return analyzeGreater(model, node, 0);
            case INTRINCIC_OP_LESS:
                return analyzeLess(model, node, 1);
            case INTRINCIC_OP_LESS_EQ:
                return analyzeLess(model, node, 0);
            case INTRINCIC_OP_EQUAL:
                return analyzeEquals(translator, model, node, m);
            case INTRINCIC_OP_NOT_EQ:
                return analyzeNotEquals(translator, model, node, m);
            case INTRINCIC_OP_NOT:
                return Chars.equalsLowerCaseAscii(node.rhs.token, "in") && analyzeNotIn(translator, model, node, m);
            default:
                return false;
        }
    }

    private void replaceAllWithOverlap(IntrinsicModel model) {
        tempK.clear();
        tempP.clear();
        for (int i = 0, k = tempKeys.size(); i < k; i++) {
            if (model.keyValues.contains(tempKeys.get(i)) && tempK.add(tempKeys.get(i))) {
                tempP.add(tempPos.get(i));
            }
        }

        if (tempK.size() > 0) {
            model.keyValues.clear();
            model.keyValuePositions.clear();
            model.keyValues.addAll(tempK);
            model.keyValuePositions.addAll(tempP);
        } else {
            model.intrinsicValue = IntrinsicModel.FALSE;
        }
    }

    void reset() {
        this.models.clear();
        this.stack.clear();
        this.keyNodes.clear();
        this.csPool.clear();
    }

    /**
     * Removes quotes and creates immutable char sequence. When value is not quated it is returned verbatim.
     *
     * @param value immutable character sequence.
     * @return immutable character sequence without surrounding quote marks.
     */
    private CharSequence unquote(CharSequence value) {
        if (Chars.isQuoted(value)) {
            return csPool.next().of(value, 1, value.length() - 2);
        }
        return value;
    }
}
