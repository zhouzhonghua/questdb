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

package io.questdb.cairo;

import io.questdb.cutlass.text.TextConfiguration;
import io.questdb.std.FilesFacade;
import io.questdb.std.microtime.MicrosecondClock;
import io.questdb.std.time.MillisecondClock;

public interface CairoConfiguration {

    int getSqlCopyBufferSize();

    int getCopyPoolCapacity();

    int getCreateAsSelectRetryCount();

    CharSequence getDefaultMapType();

    boolean getDefaultSymbolCacheFlag();

    int getDefaultSymbolCapacity();

    int getFileOperationRetryCount();

    FilesFacade getFilesFacade();

    long getIdleCheckInterval();

    long getInactiveReaderTTL();

    long getInactiveWriterTTL();

    int getIndexValueBlockSize();

    int getMaxSwapFileCount();

    MicrosecondClock getMicrosecondClock();

    MillisecondClock getMillisecondClock();

    int getMkDirMode();

    int getParallelIndexThreshold();

    int getReaderPoolMaxSegments();

    CharSequence getRoot();

    long getSpinLockTimeoutUs();

    int getSqlCacheBlocks();

    int getSqlCacheRows();

    int getSqlCharacterStoreCapacity();

    int getSqlCharacterStoreSequencePoolCapacity();

    int getSqlColumnPoolCapacity();

    double getSqlCompactMapLoadFactor();

    int getSqlExpressionPoolCapacity();

    double getSqlFastMapLoadFactor();

    int getSqlJoinContextPoolCapacity();

    int getSqlLexerPoolCapacity();

    int getSqlMapKeyCapacity();

    int getSqlMapPageSize();

    int getSqlModelPoolCapacity();

    int getSqlSortKeyPageSize();

    int getSqlSortLightValuePageSize();

    int getSqlHashJoinValuePageSize();

    long getSqlLatestByRowCount();

    int getSqlHashJoinLightValuePageSize();

    int getSqlSortValuePageSize();

    TextConfiguration getTextConfiguration();

    long getWorkStealTimeoutNanos();

    boolean isParallelIndexingEnabled();

    /**
     * This holds table metadata, which is usually quite small. 16K page should be adequate.
     *
     * @return memory page size
     */
    int getSqlJoinMetadataPageSize();

    int getAnalyticColumnPoolCapacity();

    int getCreateTableModelPoolCapacity();

    int getColumnCastModelPoolCapacity();

    int getRenameTableModelPoolCapacity();

    int getWithClauseModelPoolCapacity();

    int getInsertPoolCapacity();

    int getCommitMode();
}
