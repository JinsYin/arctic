package com.netease.arctic.ams.server.optimize;

import com.netease.arctic.ams.api.OptimizeStatus;
import com.netease.arctic.ams.server.model.BasicOptimizeTask;
import com.netease.arctic.ams.server.model.OptimizeTaskRuntime;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.utils.JDBCSqlSessionFactoryProvider;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.utils.SerializationUtils;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@PrepareForTest({
    JDBCSqlSessionFactoryProvider.class
})
@PowerMockIgnore({"org.apache.logging.log4j.*", "javax.management.*", "org.apache.http.conn.ssl.*",
    "com.amazonaws.http.conn.ssl.*",
    "javax.net.ssl.*", "org.apache.hadoop.*", "javax.*", "com.sun.org.apache.*", "org.apache.xerces.*"})
public class TestIcebergMinorOptimizeCommit extends TestIcebergBase {
  @Test
  public void testNoPartitionTableMinorOptimizeCommit() throws Exception {
    icebergNoPartitionTable.asUnkeyedTable().updateProperties()
        .set(TableProperties.SELF_OPTIMIZING_FRAGMENT_RATIO,
            TableProperties.SELF_OPTIMIZING_TARGET_SIZE_DEFAULT / 1000 + "")
        .commit();
    List<DataFile> dataFiles = insertDataFiles(icebergNoPartitionTable.asUnkeyedTable(), 10);
    insertEqDeleteFiles(icebergNoPartitionTable.asUnkeyedTable(), 5);
    insertPosDeleteFiles(icebergNoPartitionTable.asUnkeyedTable(), dataFiles);
    Set<String> oldDataFilesPath = new HashSet<>();
    Set<String> oldDeleteFilesPath = new HashSet<>();
    try (CloseableIterable<FileScanTask> filesIterable = icebergNoPartitionTable.asUnkeyedTable().newScan()
        .planFiles()) {
      filesIterable.forEach(fileScanTask -> {
        if (fileScanTask.file().fileSizeInBytes() <= 1000) {
          oldDataFilesPath.add((String) fileScanTask.file().path());
          fileScanTask.deletes().forEach(deleteFile -> oldDeleteFilesPath.add((String) deleteFile.path()));
        }
      });
    }

    List<FileScanTask> fileScanTasks;
    try (CloseableIterable<FileScanTask> filesIterable = icebergNoPartitionTable.asUnkeyedTable().newScan()
        .planFiles()) {
      fileScanTasks = Lists.newArrayList(filesIterable);
    }

    icebergNoPartitionTable.asUnkeyedTable().newScan().planFiles();
    IcebergMinorOptimizePlan optimizePlan = new IcebergMinorOptimizePlan(icebergNoPartitionTable,
        new TableOptimizeRuntime(icebergNoPartitionTable.id()),
        fileScanTasks, 1, System.currentTimeMillis(),
        icebergNoPartitionTable.asUnkeyedTable().currentSnapshot().snapshotId());
    List<BasicOptimizeTask> tasks = optimizePlan.plan().getOptimizeTasks();

    List<DataFile> resultDataFiles = insertOptimizeTargetDataFiles(icebergNoPartitionTable.asUnkeyedTable(), 10);
    List<DeleteFile> resultDeleteFiles = insertPosDeleteFiles(icebergNoPartitionTable.asUnkeyedTable(), resultDataFiles);
    List<ContentFile<?>> resultFiles = new ArrayList<>();
    resultFiles.addAll(resultDataFiles);
    resultFiles.addAll(resultDeleteFiles);
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      OptimizeTaskRuntime optimizeRuntime = new OptimizeTaskRuntime(task.getTaskId());
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      if (resultFiles != null) {
        optimizeRuntime.setNewFileSize(resultFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(resultFiles.stream().map(SerializationUtils::toByteBuffer).collect(Collectors.toList()));
      }
      List<ByteBuffer> finalTargetFiles = optimizeRuntime.getTargetFiles();
      optimizeRuntime.setTargetFiles(finalTargetFiles);
      optimizeRuntime.setNewFileCnt(finalTargetFiles.size());
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    IcebergOptimizeCommit optimizeCommit = new IcebergOptimizeCommit(icebergNoPartitionTable, partitionTasks);
    optimizeCommit.commit(icebergNoPartitionTable.asUnkeyedTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    Set<String> newDeleteFilesPath = new HashSet<>();
    try (CloseableIterable<FileScanTask> filesIterable = icebergPartitionTable.asUnkeyedTable().newScan()
        .planFiles()) {
      filesIterable.forEach(fileScanTask -> {
        if (fileScanTask.file().fileSizeInBytes() <= 1000) {
          newDataFilesPath.add((String) fileScanTask.file().path());
          fileScanTask.deletes().forEach(deleteFile -> newDeleteFilesPath.add((String) deleteFile.path()));
        }
      });
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);
    Assert.assertNotEquals(oldDeleteFilesPath, newDeleteFilesPath);
  }

  @Test
  public void testPartitionTableMinorOptimizeCommit() throws Exception {
    icebergPartitionTable.asUnkeyedTable().updateProperties()
        .set(TableProperties.SELF_OPTIMIZING_FRAGMENT_RATIO,
            TableProperties.SELF_OPTIMIZING_TARGET_SIZE_DEFAULT / 1000 + "")
        .commit();
    List<DataFile> dataFiles = insertDataFiles(icebergPartitionTable.asUnkeyedTable(), 10);
    insertEqDeleteFiles(icebergPartitionTable.asUnkeyedTable(), 5);
    insertPosDeleteFiles(icebergPartitionTable.asUnkeyedTable(), dataFiles);
    Set<String> oldDataFilesPath = new HashSet<>();
    Set<String> oldDeleteFilesPath = new HashSet<>();
    try (CloseableIterable<FileScanTask> filesIterable = icebergPartitionTable.asUnkeyedTable().newScan()
        .planFiles()) {
      filesIterable.forEach(fileScanTask -> {
        oldDataFilesPath.add((String) fileScanTask.file().path());
        fileScanTask.deletes().forEach(deleteFile -> oldDeleteFilesPath.add((String) deleteFile.path()));
      });
    }

    List<FileScanTask> fileScanTasks;
    try (CloseableIterable<FileScanTask> filesIterable = icebergPartitionTable.asUnkeyedTable().newScan()
        .planFiles()) {
      fileScanTasks = Lists.newArrayList(filesIterable);
    }

    IcebergMinorOptimizePlan optimizePlan = new IcebergMinorOptimizePlan(icebergPartitionTable,
        new TableOptimizeRuntime(icebergPartitionTable.id()),
        fileScanTasks, 1, System.currentTimeMillis(),
        icebergPartitionTable.asUnkeyedTable().currentSnapshot().snapshotId());
    List<BasicOptimizeTask> tasks = optimizePlan.plan().getOptimizeTasks();

    List<DataFile> resultDataFiles = insertOptimizeTargetDataFiles(icebergPartitionTable.asUnkeyedTable(), 10);
    List<DeleteFile> resultDeleteFiles = insertPosDeleteFiles(icebergPartitionTable.asUnkeyedTable(), resultDataFiles);
    List<ContentFile<?>> resultFiles = new ArrayList<>();
    resultFiles.addAll(resultDataFiles);
    resultFiles.addAll(resultDeleteFiles);
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      OptimizeTaskRuntime optimizeRuntime = new OptimizeTaskRuntime(task.getTaskId());
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      if (resultFiles != null) {
        optimizeRuntime.setNewFileSize(resultFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(resultFiles.stream().map(SerializationUtils::toByteBuffer).collect(Collectors.toList()));
      }
      List<ByteBuffer> finalTargetFiles = optimizeRuntime.getTargetFiles();
      optimizeRuntime.setTargetFiles(finalTargetFiles);
      optimizeRuntime.setNewFileCnt(finalTargetFiles.size());
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    IcebergOptimizeCommit optimizeCommit = new IcebergOptimizeCommit(icebergPartitionTable, partitionTasks);
    optimizeCommit.commit(icebergPartitionTable.asUnkeyedTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    Set<String> newDeleteFilesPath = new HashSet<>();
    try (CloseableIterable<FileScanTask> filesIterable = icebergPartitionTable.asUnkeyedTable().newScan()
        .planFiles()) {
      filesIterable.forEach(fileScanTask -> {
        if (fileScanTask.file().fileSizeInBytes() <= 1000) {
          newDataFilesPath.add((String) fileScanTask.file().path());
          fileScanTask.deletes().forEach(deleteFile -> newDeleteFilesPath.add((String) deleteFile.path()));
        }
      });
    }
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);
    Assert.assertNotEquals(oldDeleteFilesPath, newDeleteFilesPath);
  }
}
