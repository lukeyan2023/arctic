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

package com.netease.arctic.hive.catalog;

import com.netease.arctic.AmsClient;
import com.netease.arctic.ams.api.CatalogMeta;
import com.netease.arctic.ams.api.TableMeta;
import com.netease.arctic.ams.api.properties.MetaTableProperties;
import com.netease.arctic.catalog.BaseArcticCatalog;
import com.netease.arctic.hive.CachedHiveClientPool;
import com.netease.arctic.hive.table.KeyedHiveTable;
import com.netease.arctic.hive.table.UnkeyedHiveTable;
import com.netease.arctic.hive.utils.HiveSchemaUtil;
import com.netease.arctic.io.ArcticFileIO;
import com.netease.arctic.io.ArcticHadoopFileIO;
import com.netease.arctic.table.BaseKeyedTable;
import com.netease.arctic.table.BaseTable;
import com.netease.arctic.table.ChangeTable;
import com.netease.arctic.table.TableBuilder;
import com.netease.arctic.table.TableIdentifier;
import com.netease.arctic.table.TableProperties;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implementation of {@link com.netease.arctic.catalog.ArcticCatalog} to support Hive table as base store.
 */
public class ArcticHiveCatalog extends BaseArcticCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(ArcticHiveCatalog.class);

  private CachedHiveClientPool hiveClientPool;

  @Override
  public void initialize(
      AmsClient client, CatalogMeta meta, Map<String, String> properties) {
    super.initialize(client, meta, properties);
    this.hiveClientPool = new CachedHiveClientPool(tableMetaStore, properties);
  }

  @Override
  public List<String> listDatabases() {
    try {
      return hiveClientPool.run(HiveMetaStoreClient::getAllDatabases);
    } catch (TException | InterruptedException e) {
      throw new RuntimeException("Failed to list databases", e);
    }
  }

  @Override
  public void createDatabase(String databaseName) {
    try {
      hiveClientPool.run(client -> {
        Database database = new Database();
        database.setName(databaseName);
        client.createDatabase(database);
        return null;
      });
    } catch (TException | InterruptedException e) {
      throw new RuntimeException("Failed to create database:" + databaseName, e);
    }
  }

  @Override
  public void dropDatabase(String databaseName) {
    try {
      hiveClientPool.run(client -> {
        client.dropDatabase(databaseName,
            false /* deleteData */,
            false /* ignoreUnknownDb */,
            false /* cascade */);
        return null;
      });
    } catch (TException | InterruptedException e) {
      throw new RuntimeException("Failed to drop database:" + databaseName, e);
    }
  }

  @Override
  protected void doDropTable(TableMeta meta, boolean purge) {
    super.doDropTable(meta, purge);
    try {
      hiveClientPool.run(client -> {
        client.dropTable(meta.getTableIdentifier().getDatabase(),
            meta.getTableIdentifier().getTableName(),
            purge /* deleteData */,
            false /* ignoreUnknownTab */);
        return null;
      });
    } catch (TException | InterruptedException e) {
      throw new RuntimeException("Failed to drop table:" + meta.getTableIdentifier(), e);
    }
  }

  @Override
  public TableBuilder newTableBuilder(
      TableIdentifier identifier, Schema schema) {
    return new ArcticHiveTableBuilder(identifier, schema);
  }

  @Override
  protected KeyedHiveTable loadKeyedTable(TableMeta tableMeta) {
    TableIdentifier tableIdentifier = TableIdentifier.of(tableMeta.getTableIdentifier());
    String tableLocation = checkLocation(tableMeta, MetaTableProperties.LOCATION_KEY_TABLE);
    String baseLocation = checkLocation(tableMeta, MetaTableProperties.LOCATION_KEY_BASE);
    String changeLocation = checkLocation(tableMeta, MetaTableProperties.LOCATION_KEY_CHANGE);

    ArcticFileIO fileIO = new ArcticHadoopFileIO(tableMetaStore);
    Table baseIcebergTable = tableMetaStore.doAs(() -> tables.load(baseLocation));
    BaseTable baseTable = new KeyedHiveTable.HiveBaseInternalTable(tableIdentifier,
        useArcticTableOperations(baseIcebergTable, baseLocation, fileIO, tableMetaStore.getConfiguration()),
        fileIO, client);

    Table changeIcebergTable = tableMetaStore.doAs(() -> tables.load(changeLocation));
    ChangeTable changeTable = new BaseKeyedTable.ChangeInternalTable(tableIdentifier,
        useArcticTableOperations(changeIcebergTable, changeLocation, fileIO, tableMetaStore.getConfiguration()),
        fileIO, client);
    return new KeyedHiveTable(tableMeta, tableLocation,
        buildPrimaryKeySpec(baseTable.schema(), tableMeta), client, baseTable, changeTable);
  }

  @Override
  protected UnkeyedHiveTable loadUnKeyedTable(TableMeta tableMeta) {
    TableIdentifier tableIdentifier = TableIdentifier.of(tableMeta.getTableIdentifier());
    String baseLocation = checkLocation(tableMeta, MetaTableProperties.LOCATION_KEY_BASE);
    Table table = tableMetaStore.doAs(() -> tables.load(baseLocation));
    ArcticFileIO arcticFileIO = new ArcticHadoopFileIO(tableMetaStore);
    return new UnkeyedHiveTable(tableIdentifier, useArcticTableOperations(table, baseLocation,
        arcticFileIO, tableMetaStore.getConfiguration()), arcticFileIO, client);
  }

  class ArcticHiveTableBuilder extends BaseArcticTableBuilder {

    public ArcticHiveTableBuilder(TableIdentifier identifier, Schema schema) {
      super(identifier, schema);
    }

    @Override
    protected void doCreateCheck() {
      super.doCreateCheck();
      try {
        org.apache.hadoop.hive.metastore.api.Table hiveTable =
            hiveClientPool.run(client -> client.getTable(identifier.getDatabase(),
            identifier.getTableName()));
        if (hiveTable != null) {
          throw new IllegalArgumentException("Table is already existed in hive meta store:" + identifier);
        }
      } catch (org.apache.hadoop.hive.metastore.api.NoSuchObjectException noSuchObjectException) {
        // ignore this exception
      } catch (TException | InterruptedException e) {
        throw new RuntimeException("Failed to check table exist:" + identifier, e);
      }
      if (!partitionSpec.isUnpartitioned()) {
        for (PartitionField partitionField : partitionSpec.fields()) {
          if (!partitionField.transform().isIdentity()) {
            throw new IllegalArgumentException("Unsupported partition transform:" +
                partitionField.transform().toString());
          }
        }
      }
    }

    @Override
    protected KeyedHiveTable createKeyedTable(TableMeta meta) {
      TableIdentifier tableIdentifier = TableIdentifier.of(meta.getTableIdentifier());
      String tableLocation = checkLocation(meta, MetaTableProperties.LOCATION_KEY_TABLE);
      String baseLocation = checkLocation(meta, MetaTableProperties.LOCATION_KEY_BASE);
      String changeLocation = checkLocation(meta, MetaTableProperties.LOCATION_KEY_CHANGE);

      Map<String, String> tableProperties = meta.getProperties();
      tableProperties.put(TableProperties.TABLE_CREATE_TIME, String.valueOf(System.currentTimeMillis()));
      tableProperties.put(org.apache.iceberg.TableProperties.FORMAT_VERSION, "2");

      ArcticFileIO fileIO = new ArcticHadoopFileIO(tableMetaStore);
      Table baseIcebergTable = tableMetaStore.doAs(() -> {
        try {
          return tables.create(schema, partitionSpec, tableProperties, baseLocation);
        } catch (Exception e) {
          throw new IllegalStateException("create base table failed", e);
        }
      });
      BaseTable baseTable = new KeyedHiveTable.HiveBaseInternalTable(tableIdentifier,
          useArcticTableOperations(baseIcebergTable, baseLocation, fileIO, tableMetaStore.getConfiguration()),
          fileIO, client);

      Table changeIcebergTable = tableMetaStore.doAs(() -> {
        try {
          return tables.create(schema, partitionSpec, tableProperties, changeLocation);
        } catch (Exception e) {
          throw new IllegalStateException("create change table failed", e);
        }
      });
      ChangeTable changeTable = new BaseKeyedTable.ChangeInternalTable(tableIdentifier,
          useArcticTableOperations(changeIcebergTable, changeLocation, fileIO, tableMetaStore.getConfiguration()),
          fileIO, client);

      try {
        hiveClientPool.run(client -> {
          org.apache.hadoop.hive.metastore.api.Table hiveTable = newHiveTable(meta);
          hiveTable.setSd(storageDescriptor(tableLocation,
              FileFormat.valueOf(PropertyUtil.propertyAsString(properties, TableProperties.DEFAULT_FILE_FORMAT,
                  TableProperties.DEFAULT_FILE_FORMAT_DEFAULT).toUpperCase(Locale.ENGLISH))));
          client.createTable(hiveTable);
          return null;
        });
      } catch (TException | InterruptedException e) {
        throw new RuntimeException("Failed to create hive table:" + meta.getTableIdentifier(), e);
      }
      return new KeyedHiveTable(meta, tableLocation,
          primaryKeySpec, client, baseTable, changeTable);
    }

    @Override
    protected UnkeyedHiveTable createUnKeyedTable(TableMeta meta) {
      TableIdentifier tableIdentifier = TableIdentifier.of(meta.getTableIdentifier());
      String baseLocation = checkLocation(meta, MetaTableProperties.LOCATION_KEY_BASE);
      String tableLocation = checkLocation(meta, MetaTableProperties.LOCATION_KEY_TABLE);

      Map<String, String> tableProperties = meta.getProperties();
      tableProperties.put(TableProperties.TABLE_CREATE_TIME, String.valueOf(System.currentTimeMillis()));
      Table table = tableMetaStore.doAs(() -> {
        try {
          return tables.create(schema, partitionSpec, meta.getProperties(), baseLocation);
        } catch (Exception e) {
          throw new IllegalStateException("create table failed", e);
        }
      });
      try {
        hiveClientPool.run(client -> {
          org.apache.hadoop.hive.metastore.api.Table hiveTable = newHiveTable(meta);
          hiveTable.setSd(storageDescriptor(tableLocation,
              FileFormat.valueOf(PropertyUtil.propertyAsString(properties, TableProperties.BASE_FILE_FORMAT,
                  TableProperties.BASE_FILE_FORMAT_DEFAULT).toUpperCase(Locale.ENGLISH))));
          client.createTable(hiveTable);
          return null;
        });
      } catch (TException | InterruptedException e) {
        throw new RuntimeException("Failed to create hive table:" + meta.getTableIdentifier(), e);
      }
      ArcticFileIO fileIO = new ArcticHadoopFileIO(tableMetaStore);
      return new UnkeyedHiveTable(tableIdentifier, useArcticTableOperations(table, baseLocation, fileIO,
          tableMetaStore.getConfiguration()), fileIO, client);
    }

    private org.apache.hadoop.hive.metastore.api.Table newHiveTable(TableMeta meta) {
      final long currentTimeMillis = System.currentTimeMillis();

      org.apache.hadoop.hive.metastore.api.Table newTable = new org.apache.hadoop.hive.metastore.api.Table(
          meta.getTableIdentifier().getTableName(),
          meta.getTableIdentifier().getDatabase(),
          System.getProperty("user.name"),
          (int) currentTimeMillis / 1000,
          (int) currentTimeMillis / 1000,
          Integer.MAX_VALUE,
          null,
          HiveSchemaUtil.hivePartitionFields(schema, partitionSpec),
          new HashMap<>(),
          null,
          null,
          TableType.EXTERNAL_TABLE.toString());

      newTable.getParameters().put("EXTERNAL", "TRUE"); // using the external table type also requires this
      return newTable;
    }

    private StorageDescriptor storageDescriptor(String location, FileFormat format) {
      final StorageDescriptor storageDescriptor = new StorageDescriptor();
      storageDescriptor.setCols(HiveSchemaUtil.hiveTableFields(schema, partitionSpec));
      storageDescriptor.setLocation(location + "/hive_data");
      SerDeInfo serDeInfo = new SerDeInfo();
      switch (format) {
        case PARQUET:
          storageDescriptor.setOutputFormat("org.apache.hadoop.mapred.FileOutputFormat");
          storageDescriptor.setInputFormat("org.apache.hadoop.mapred.FileInputFormat");
          serDeInfo.setSerializationLib("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
          break;
        default:
          throw new IllegalArgumentException("Unsupported hive table file format:" + format);
      }

      storageDescriptor.setSerdeInfo(serDeInfo);
      return storageDescriptor;
    }

    @Override
    protected String getDatabaseLocation() {
      try {
        return hiveClientPool.run(client -> client.getDatabase(identifier.getDatabase()).getLocationUri());
      } catch (TException | InterruptedException e) {
        throw new RuntimeException("Failed to get database location:" + identifier.getDatabase(), e);
      }
    }

    @Override
    protected void doRollbackCreateTable(TableMeta meta) {
      super.doRollbackCreateTable(meta);
      try {
        hiveClientPool.run(client -> {
          client.dropTable(meta.getTableIdentifier().getDatabase(),
              meta.getTableIdentifier().getTableName(),
              true,
              true);
          return null;
        });
      } catch (TException | InterruptedException e) {
        LOG.warn("Failed to drop hive table while rolling back create table operation", e);
      }
    }
  }

}
