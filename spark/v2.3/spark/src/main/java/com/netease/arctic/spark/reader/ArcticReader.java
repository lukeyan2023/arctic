package com.netease.arctic.spark.reader;

import com.netease.arctic.io.ArcticFileIO;
import com.netease.arctic.scan.CombinedScanTask;
import com.netease.arctic.scan.KeyedTableScan;
import com.netease.arctic.scan.KeyedTableScanTask;
import com.netease.arctic.spark.util.Stats;
import com.netease.arctic.table.KeyedTable;
import com.netease.arctic.table.PrimaryKeySpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.expressions.Binder;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.CloseableIterator;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.spark.SparkFilters;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.sources.v2.reader.DataReader;
import org.apache.spark.sql.sources.v2.reader.DataReaderFactory;
import org.apache.spark.sql.sources.v2.reader.DataSourceReader;
import org.apache.spark.sql.sources.v2.reader.Statistics;
import org.apache.spark.sql.sources.v2.reader.SupportsPushDownFilters;
import org.apache.spark.sql.sources.v2.reader.SupportsPushDownRequiredColumns;
import org.apache.spark.sql.sources.v2.reader.SupportsReportStatistics;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class ArcticReader implements DataSourceReader,
    SupportsPushDownFilters, SupportsPushDownRequiredColumns, SupportsReportStatistics {
  private static final Logger LOG = LoggerFactory.getLogger(ArcticReader.class);
  private static final Filter[] NO_FILTERS = new Filter[0];

  private final KeyedTable table;

  private Schema expectedSchema = null;
  private StructType requestedProjection;
  private StructType readSchema = null;
  private final boolean caseSensitive;
  private List<Expression> filterExpressions = null;
  private Filter[] pushedFilters = NO_FILTERS;
  private List<CombinedScanTask> tasks = null;

  public ArcticReader(KeyedTable table, Schema expectedSchema, boolean caseSensitive) {
    this.table = table;
    this.expectedSchema = expectedSchema;
    this.caseSensitive = caseSensitive;
  }

  @Override
  public Filter[] pushFilters(Filter[] filters) {
    List<Expression> expressions = Lists.newArrayListWithExpectedSize(filters.length);
    List<Filter> pushed = Lists.newArrayListWithExpectedSize(filters.length);

    for (Filter filter : filters) {
      Expression expr = SparkFilters.convert(filter);
      if (expr != null) {
        try {
          Binder.bind(table.schema().asStruct(), expr, caseSensitive);
          expressions.add(expr);
          pushed.add(filter);
        } catch (ValidationException e) {
          // binding to the table schema failed, so this expression cannot be pushed down
        }
      }
    }

    this.filterExpressions = expressions;
    this.pushedFilters = pushed.toArray(new Filter[0]);

    // Spark doesn't support residuals per task, so return all filters
    // to get Spark to handle record-level filtering
    return filters;
  }

  @Override
  public Filter[] pushedFilters() {
    return pushedFilters;
  }

  @Override
  public void pruneColumns(StructType requiredSchema) {
    this.requestedProjection = requiredSchema;
  }

  @Override
  public Statistics getStatistics() {
    long sizeInBytes = 0L;
    long numRows = 0L;

    for (CombinedScanTask combinedScanTask : tasks()) {
      for (KeyedTableScanTask fileScanTask : combinedScanTask.tasks()) {

        sizeInBytes += fileScanTask.cost();
        numRows += fileScanTask.recordCount();
      }
    }

    return new Stats(sizeInBytes, numRows);
  }

  @Override
  public StructType readSchema() {
    if (readSchema == null) {
      this.readSchema = SparkSchemaUtil.convert(expectedSchema);
    }
    return readSchema;
  }

  @Override
  public List<DataReaderFactory<Row>> createDataReaderFactories() {
    List<CombinedScanTask> scanTasks = tasks();
    ReadTask[] readTasks  = new ReadTask[scanTasks.size()];
    for (int i = 0; i < scanTasks.size(); i++) {
      readTasks[i] = new ReadTask(scanTasks.get(i), table, expectedSchema,
          caseSensitive);
    }
    return Arrays.asList(readTasks);
  }

  private static class ReadTask implements Serializable, DataReaderFactory<Row> {
    final CombinedScanTask combinedScanTask;
    final ArcticFileIO io;
    final boolean caseSensitive;
    final Schema expectedSchema;
    final Schema tableSchema;
    final PrimaryKeySpec keySpec;

    ReadTask(
        CombinedScanTask combinedScanTask,
        KeyedTable table,
        Schema expectedSchema,
        boolean caseSensitive) {
      this.combinedScanTask = combinedScanTask;
      this.expectedSchema = expectedSchema;
      this.tableSchema = table.schema();
      this.caseSensitive = caseSensitive;
      this.io = table.io();
      this.keySpec = table.primaryKeySpec();
    }

    @Override
    public String[] preferredLocations() {
      return DataReaderFactory.super.preferredLocations();
    }

    @Override
    public DataReader<Row> createDataReader() {
      return new RowReader(io, tableSchema, expectedSchema, keySpec, null, caseSensitive, combinedScanTask);
    }
  }

  private static class RowReader implements DataReader<Row> {

    ArcticSparkDataReader reader;
    Iterator<KeyedTableScanTask> scanTasks;
    KeyedTableScanTask currentScanTask;
    CloseableIterator<Row> currentIterator = CloseableIterator.empty();
    Row current;

    RowReader(ArcticFileIO fileIO,
              Schema tableSchema,
              Schema projectedSchema,
              PrimaryKeySpec primaryKeySpec,
              String nameMapping,
              boolean caseSensitive,
              CombinedScanTask combinedScanTask) {
      reader = new ArcticSparkDataReader(
          fileIO, tableSchema, projectedSchema, primaryKeySpec,
          nameMapping, caseSensitive);
      scanTasks = combinedScanTask.tasks().iterator();
    }

    @Override
    public boolean next() throws IOException {
      while (true) {
        if (currentIterator.hasNext()) {
          this.current = currentIterator.next();
          return true;
        } else if (scanTasks.hasNext()) {
          this.currentIterator.close();
          this.currentScanTask = scanTasks.next();
          this.currentIterator = reader.readData(this.currentScanTask);
        } else {
          this.currentIterator.close();
          return false;
        }
      }
    }

    @Override
    public Row get() {
      return this.current;
    }

    @Override
    public void close() throws IOException {
      this.currentIterator.close();
      while (scanTasks.hasNext()) {
        scanTasks.next();
      }
    }
  }



  private List<CombinedScanTask> tasks() {
    if (tasks == null) {
      KeyedTableScan scan = table
          .newScan();

      if (filterExpressions != null) {
        for (Expression filter : filterExpressions) {
          scan = scan.filter(filter);
        }
      }
      long startTime = System.currentTimeMillis();
      LOG.info("mor statistics plan task start");
      try (CloseableIterable<CombinedScanTask> tasksIterable = scan.planTasks()) {
        this.tasks = Lists.newArrayList(tasksIterable);
        LOG.info("mor statistics plan task end, cost time {}, tasks num {}",
            System.currentTimeMillis() - startTime, tasks.size());
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to close table scan: %s", e);
      }
    }
    return tasks;
  }
}
