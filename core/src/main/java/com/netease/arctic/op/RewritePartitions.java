/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.op;

import com.netease.arctic.table.BaseTable;
import com.netease.arctic.table.KeyedTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.util.StructLikeMap;

import java.util.List;

/**
 * Replace {@link BaseTable} partition files and change max transaction id map
 */
public class RewritePartitions extends PartitionTransactionOperation {

  private List<DataFile> addFiles = Lists.newArrayList();
  private Long transactionId;
  
  public RewritePartitions(KeyedTable keyedTable) {
    super(keyedTable);
  }

  public RewritePartitions addDataFile(DataFile dataFile) {
    this.addFiles.add(dataFile);
    return this;
  }

  public RewritePartitions withTransactionId(long transactionId) {
    this.transactionId = transactionId;
    return this;
  }

  @Override
  protected StructLikeMap<Long> apply(Transaction transaction, StructLikeMap<Long> partitionMaxTxId) {
    if (this.addFiles.isEmpty()) {
      return partitionMaxTxId;
    }

    Preconditions.checkNotNull(transactionId, "transaction-Id must be set.");
    Preconditions.checkArgument(transactionId > 0, "transaction-Id must be positive.");

    ReplacePartitions replacePartitions = transaction.newReplacePartitions();
    addFiles.forEach(replacePartitions::addFile);
    replacePartitions.commit();

    addFiles.forEach(f -> {
      StructLike pd = f.partition();
      long txId = partitionMaxTxId.containsKey(pd) ? partitionMaxTxId.get(pd) : -1;
      txId = Math.max(txId, transactionId);
      partitionMaxTxId.put(pd, txId);
    });
    return partitionMaxTxId;
  }
}
