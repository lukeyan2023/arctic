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

package com.netease.arctic.spark.hive;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.*;
import org.apache.iceberg.common.DynConstructors;
import org.apache.iceberg.common.DynMethods;
import org.apache.iceberg.hadoop.Util;
import org.apache.iceberg.hive.HiveClientPool;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HMSMockServer {

  public static final Logger LOG = LoggerFactory.getLogger(HMSMockServer.class);

  private static final String DEFAULT_DATABASE_NAME = "default";
  private static final int DEFAULT_POOL_SIZE = 5;



  // create the metastore handlers based on whether we're working with Hive2 or Hive3 dependencies
  // we need to do this because there is a breaking API change between Hive2 and Hive3
  private static final DynConstructors.Ctor<HiveMetaStore.HMSHandler> HMS_HANDLER_CTOR = DynConstructors.builder()
      .impl(HiveMetaStore.HMSHandler.class, String.class, Configuration.class)
      .impl(HiveMetaStore.HMSHandler.class, String.class, HiveConf.class)
      .build();

  private static final DynMethods.StaticMethod GET_BASE_HMS_HANDLER = DynMethods.builder("getProxy")
      .impl(RetryingHMSHandler.class, Configuration.class, IHMSHandler.class, boolean.class)
      .impl(RetryingHMSHandler.class, HiveConf.class, IHMSHandler.class, boolean.class)
      .buildStatic();

  // Hive3 introduces background metastore tasks (MetastoreTaskThread) for performing various cleanup duties. These
  // threads are scheduled and executed in a static thread pool (org.apache.hadoop.com.netease.spark.hive.metastore.ThreadPool).
  // This thread pool is shut down normally as part of the JVM shutdown hook, but since we're creating and tearing down
  // multiple metastore instances within the same JVM, we have to call this cleanup method manually, otherwise
  // threads from our previous test suite will be stuck in the pool with stale config, and keep on being scheduled.
  // This can lead to issues, e.g. accidental Persistence Manager closure by ScheduledQueryExecutionsMaintTask.
  private static final DynMethods.StaticMethod METASTORE_THREADS_SHUTDOWN = DynMethods.builder("shutdown")
      .impl("org.apache.hadoop.com.netease.spark.hive.metastore.ThreadPool")
      .orNoop()
      .buildStatic();

  private File hiveLocalDir;
  private HiveConf hiveConf;
  private ExecutorService executorService;
  private TServer server;
  private HiveMetaStore.HMSHandler baseHandler;
  private HiveClientPool clientPool;
  private int port ;
  private HiveMetaStoreClient client ;

  public HMSMockServer(){
    this(new File("hive_warehouse"));
  }

  public HMSMockServer(File file){
    this.hiveLocalDir = file;
    // if (file.exists()){
    //
    // }
    int port = new Random().nextInt(4000);
    this.port = port + 24000;
    this.hiveConf = newHiveConf(this.port);
  }


  /**
   * Starts a TestHiveMetastore with the default connection pool size (5).
   */
  public void start() {
    LOG.info("-------------------------------------------------------------------------");
    LOG.info("    Starting HiveMetastoreServer ");
    LOG.info("-------------------------------------------------------------------------");

    start(DEFAULT_POOL_SIZE);
  }

  /**
   * Starts a TestHiveMetastore with a provided connection pool size.
   * @param poolSize The number of threads in the executor pool
   */
  public void start(int poolSize) {
    try {
      LOG.info("com.netease.spark.hive local dir: " + hiveLocalDir.getAbsolutePath());
      FileUtils.deleteQuietly(hiveLocalDir);

      File derbyLogFile = new File(hiveLocalDir, "derby.log");
      System.setProperty("derby.stream.error.file", derbyLogFile.getAbsolutePath());


      TServerSocket socket = new TServerSocket(port);
      this.server = newThriftServer(socket, poolSize, hiveConf);
      this.executorService = Executors.newSingleThreadExecutor();
      this.executorService.submit(() -> server.serve());

      // in Hive3, setting this as a system prop ensures that it will be picked up whenever a new HiveConf is created
      System.setProperty(HiveConf.ConfVars.METASTOREURIS.varname, hiveConf.getVar(HiveConf.ConfVars.METASTOREURIS));

      this.clientPool = new HiveClientPool(1, hiveConf);
    } catch (Exception e) {
      throw new RuntimeException("Cannot start TestHiveMetastore", e);
    }
  }

  public int getMetastorePort(){
    return this.port;
  }

  public void stop() {
    LOG.info("-------------------------------------------------------------------------");
    LOG.info("    Shutdown HiveMetastoreServer ");
    LOG.info("-------------------------------------------------------------------------");

    if (clientPool != null) {
      clientPool.close();
    }
    if (server != null) {
      server.stop();
    }
    if (executorService != null) {
      executorService.shutdown();
    }
    if (hiveLocalDir != null) {
      hiveLocalDir.delete();
    }
    if (baseHandler != null) {
      baseHandler.shutdown();
    }
    METASTORE_THREADS_SHUTDOWN.invoke();

    LOG.info("-------------------------------------------------------------------------");
    LOG.info("    HiveMetastoreServer finished");
    LOG.info("-------------------------------------------------------------------------");
  }

  public HiveConf hiveConf() {
    return hiveConf;
  }

  public HiveClientPool clientPool() {
    return clientPool;
  }

  public String getDatabasePath(String dbName) {
    File dbDir = new File(hiveLocalDir, dbName );
    return dbDir.getAbsolutePath().replace("\\", "/");
  }

  public String getTablePath(String db, String table){
    File dbDir = new File(hiveLocalDir, db);
    File tableDir = new File(dbDir, table);
    return tableDir.getAbsolutePath().replace("\\", "/");
  }

  public void reset() throws Exception {
    for (String dbName : clientPool.run(HiveMetaStoreClient::getAllDatabases)) {
      for (String tblName : clientPool.run(client -> client.getAllTables(dbName))) {
        clientPool.run(client -> {
          client.dropTable(dbName, tblName, true, true, true);
          return null;
        });
      }

      if (!DEFAULT_DATABASE_NAME.equals(dbName)) {
        // Drop cascade, functions dropped by cascade
        clientPool.run(client -> {
          client.dropDatabase(dbName, true, true, true);
          return null;
        });
      }
    }

    Path warehouseRoot = new Path(hiveLocalDir.getAbsolutePath());
    FileSystem fs = Util.getFs(warehouseRoot, hiveConf);
    for (FileStatus fileStatus : fs.listStatus(warehouseRoot)) {
      if (!fileStatus.getPath().getName().equals("derby.log") &&
          !fileStatus.getPath().getName().equals("metastore_db")) {
        fs.delete(fileStatus.getPath(), true);
      }
    }
  }

  public HiveMetaStoreClient getClient(){
    if (client == null){
      try {
        this.client = new HiveMetaStoreClient(hiveConf);
      }catch (Exception e){
        throw new RuntimeException(e);
      }
    }
    return this.client;
  }

  private TServer newThriftServer(TServerSocket socket, int poolSize, HiveConf conf) throws Exception {
    HiveConf serverConf = new HiveConf(conf);
    String derbyPath = getDerbyPath();
    LOG.info("DerbyPath: " + derbyPath);
    serverConf.set(HiveConf.ConfVars.METASTORECONNECTURLKEY.varname, "jdbc:derby:" + derbyPath + ";create=true");
    baseHandler = HMS_HANDLER_CTOR.newInstance("new db based metaserver", serverConf);
    IHMSHandler handler = GET_BASE_HMS_HANDLER.invoke(serverConf, baseHandler, false);

    TThreadPoolServer.Args args = new TThreadPoolServer.Args(socket)
        .processor(new TSetIpAddressProcessor<>(handler))
        .transportFactory(new TTransportFactory())
        .protocolFactory(new TBinaryProtocol.Factory())
        .minWorkerThreads(poolSize)
        .maxWorkerThreads(poolSize);

    return new TThreadPoolServer(args);
  }

  private HiveConf newHiveConf(int port) {
    Configuration conf = new Configuration(false);
    HiveConf newHiveConf = new HiveConf(conf, HMSMockServer.class);
    newHiveConf.set(HiveConf.ConfVars.METASTOREURIS.varname, "thrift://localhost:" + port);
    newHiveConf.set(HiveConf.ConfVars.METASTOREWAREHOUSE.varname,
        "file:///" + hiveLocalDir.getAbsolutePath().replace("\\", "/"));
    newHiveConf.set(HiveConf.ConfVars.METASTORE_TRY_DIRECT_SQL.varname, "false");
    newHiveConf.set(HiveConf.ConfVars.METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES.varname, "false");
    newHiveConf.set(HiveConf.ConfVars.METASTORE_SCHEMA_VERIFICATION.varname, "false");
    newHiveConf.setBoolVar(HiveConf.ConfVars.HIVE_IN_TEST, true);
    newHiveConf.set("datanucleus.schema.autoCreateTables", "true");
    newHiveConf.set("com.netease.spark.hive.metastore.client.capability.check", "false");
    newHiveConf.set("iceberg.com.netease.spark.hive.client-pool-size", "2");
    return newHiveConf;
  }

  private String getDerbyPath() {
    File metastoreDB = new File(hiveLocalDir, "metastore_db");
    return  metastoreDB.getAbsolutePath().replace("\\", "/");
  }

}
