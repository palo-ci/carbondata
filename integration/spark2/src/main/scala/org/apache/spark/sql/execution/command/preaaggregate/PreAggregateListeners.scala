  /*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.command.preaaggregate

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.execution.command.AlterTableModel
import org.apache.spark.sql.execution.command.management.{CarbonAlterTableCompactionCommand, CarbonLoadDataCommand}
import org.apache.spark.sql.execution.command.partition.CarbonAlterTableDropHivePartitionCommand
import org.apache.spark.sql.parser.CarbonSpark2SqlParser

import org.apache.carbondata.common.exceptions.MetadataProcessException
import org.apache.carbondata.common.logging.{LogService, LogServiceFactory}
import org.apache.carbondata.core.datamap.Segment
import org.apache.carbondata.core.datastore.filesystem.{CarbonFile, CarbonFileFilter}
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.metadata.schema.table.{AggregationDataMapSchema, CarbonTable}
import org.apache.carbondata.core.statusmanager.{SegmentStatus, SegmentStatusManager}
import org.apache.carbondata.core.util.CarbonUtil
import org.apache.carbondata.core.util.path.CarbonTablePath
import org.apache.carbondata.events._
import org.apache.carbondata.processing.loading.events.LoadEvents.{LoadMetadataEvent, LoadTablePostExecutionEvent, LoadTablePostStatusUpdateEvent, LoadTablePreExecutionEvent, LoadTablePreStatusUpdateEvent}
import org.apache.carbondata.processing.merger.CompactionType

object AlterTableDropPartitionPreStatusListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override protected def onEvent(event: Event,
      operationContext: OperationContext) = {
    val preStatusListener = event.asInstanceOf[AlterTableDropPartitionPreStatusEvent]
    val carbonTable = preStatusListener.carbonTable
    val childDropPartitionCommands = operationContext.getProperty("dropPartitionCommands")
    if (childDropPartitionCommands != null && carbonTable.hasAggregationDataMap) {
      val childCommands =
        childDropPartitionCommands.asInstanceOf[Seq[CarbonAlterTableDropHivePartitionCommand]]
      childCommands.foreach(_.processData(SparkSession.getActiveSession.get))
    }
  }
}

trait CommitHelper {

  val LOGGER: LogService = LogServiceFactory.getLogService(this.getClass.getName)

  protected def markInProgressSegmentAsDeleted(tableStatusFile: String,
      operationContext: OperationContext,
      carbonTable: CarbonTable): Unit = {
    val loadMetaDataDetails = SegmentStatusManager.readTableStatusFile(tableStatusFile)
    val segmentBeingLoaded =
      operationContext.getProperty(carbonTable.getTableUniqueName + "_Segment").toString
    val newDetails = loadMetaDataDetails.collect {
      case detail if detail.getLoadName.equalsIgnoreCase(segmentBeingLoaded) =>
        detail.setSegmentStatus(SegmentStatus.MARKED_FOR_DELETE)
        detail
      case others => others
    }
    SegmentStatusManager.writeLoadDetailsIntoFile(tableStatusFile, newDetails)
  }

  /**
   *  Used to rename table status files for commit operation.
   */
  protected def renameDataMapTableStatusFiles(sourceFileName: String,
      destinationFileName: String, uuid: String): Boolean = {
    val oldCarbonFile = FileFactory.getCarbonFile(sourceFileName)
    val newCarbonFile = FileFactory.getCarbonFile(destinationFileName)
    if (oldCarbonFile.exists() && newCarbonFile.exists()) {
      val backUpPostFix = if (uuid.nonEmpty) {
        "_backup_" + uuid
      } else {
        ""
      }
      LOGGER.info(s"Renaming $newCarbonFile to ${destinationFileName + backUpPostFix}")
      if (newCarbonFile.renameForce(destinationFileName + backUpPostFix)) {
        LOGGER.info(s"Renaming $oldCarbonFile to $destinationFileName")
        oldCarbonFile.renameForce(destinationFileName)
      } else {
        LOGGER.info(s"Renaming $newCarbonFile to ${destinationFileName + backUpPostFix} failed")
        false
      }
    } else {
     /**
      * Tablestatus_uuid will fail when Pre-Aggregate table is not valid for compaction.
      * Hence this should return true
      */
      true
    }
  }

  /**
   * Used to remove table status files with UUID and segment folders.
   */
  protected def cleanUpStaleTableStatusFiles(
      childTables: Seq[CarbonTable],
      operationContext: OperationContext,
      uuid: String): Unit = {
    childTables.foreach { childTable =>
      val metaDataDir = FileFactory.getCarbonFile(
        CarbonTablePath.getMetadataPath(childTable.getTablePath))
      val tableStatusFiles = metaDataDir.listFiles(new CarbonFileFilter {
        override def accept(file: CarbonFile): Boolean = {
          file.getName.contains(uuid) || file.getName.contains("backup")
        }
      })
      tableStatusFiles.foreach(_.delete())
    }
  }
}

object AlterTableDropPartitionPostStatusListener extends OperationEventListener with CommitHelper {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override protected def onEvent(event: Event,
      operationContext: OperationContext) = {
    val postStatusListener = event.asInstanceOf[AlterTableDropPartitionPostStatusEvent]
    val carbonTable = postStatusListener.carbonTable
    val childDropPartitionCommands = operationContext.getProperty("dropPartitionCommands")
    val uuid = Option(operationContext.getProperty("uuid")).getOrElse("").toString
    if (childDropPartitionCommands != null && carbonTable.hasAggregationDataMap) {
      val childCommands =
        childDropPartitionCommands.asInstanceOf[Seq[CarbonAlterTableDropHivePartitionCommand]]
      val updateFailed = try {
        val renamedDataMaps = childCommands.takeWhile {
          childCommand =>
            val childCarbonTable = childCommand.table
            val oldTableSchemaPath = CarbonTablePath.getTableStatusFilePathWithUUID(
              childCarbonTable.getTablePath, uuid)
            // Generate table status file name without UUID, forExample: tablestatus
            val newTableSchemaPath = CarbonTablePath.getTableStatusFilePath(
              childCarbonTable.getTablePath)
            renameDataMapTableStatusFiles(oldTableSchemaPath, newTableSchemaPath, uuid)
        }
        // if true then the commit for one of the child tables has failed
        val commitFailed = renamedDataMaps.lengthCompare(childCommands.length) != 0
        if (commitFailed) {
          LOGGER.info("Reverting table status file to original state")
          renamedDataMaps.foreach {
            command =>
              val carbonTable = command.table
              // rename the backup tablestatus i.e tablestatus_backup_UUID to tablestatus
              val backupTableSchemaPath =
                CarbonTablePath.getTableStatusFilePath(carbonTable.getTablePath) + "_backup_" + uuid
              val tableSchemaPath = CarbonTablePath.getTableStatusFilePath(carbonTable.getTablePath)
              renameDataMapTableStatusFiles(backupTableSchemaPath, tableSchemaPath, "")
          }
        }
        commitFailed
      } finally {
        // after success/failure of commit delete all tablestatus files with UUID in their names.
        // if commit failed then remove the segment directory
        // TODO Need to handle deletion on tablestatus files with UUID in cleanup command.
        cleanUpStaleTableStatusFiles(childCommands.map(_.table),
          operationContext,
          uuid)
      }
      if (updateFailed) {
        sys.error("Failed to update table status for pre-aggregate table")
      }
    }
  }
}

object AlterTableDropPartitionMetaListener extends OperationEventListener{
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override protected def onEvent(event: Event,
      operationContext: OperationContext) = {
    val dropPartitionEvent = event.asInstanceOf[AlterTableDropPartitionMetaEvent]
    val parentCarbonTable = dropPartitionEvent.parentCarbonTable
    val partitionsToBeDropped = dropPartitionEvent.specs.flatMap(_.keys)
    val sparkSession = SparkSession.getActiveSession.get
    if (parentCarbonTable.hasAggregationDataMap) {
      // used as a flag to block direct drop partition on aggregate tables fired by the user
      operationContext.setProperty("isInternalDropCall", "true")
      // Filter out all the tables which dont have the partition being dropped.
      val childTablesWithoutPartitionColumns =
        parentCarbonTable.getTableInfo.getDataMapSchemaList.asScala.filter { dataMapSchema =>
          val childColumns = dataMapSchema.getChildSchema.getListOfColumns.asScala
          val partitionColExists = partitionsToBeDropped.forall {
            partition =>
              childColumns.exists { childColumn =>
                  childColumn.getAggFunction.isEmpty &&
                    childColumn.getParentColumnTableRelations != null &&
                  childColumn.getParentColumnTableRelations.asScala.head.getColumnName.
                    equals(partition)
              }
          }
          !partitionColExists
      }
      if (childTablesWithoutPartitionColumns.nonEmpty) {
        throw new MetadataProcessException(s"Cannot drop partition as one of the partition is not" +
                                           s" participating in the following datamaps ${
          childTablesWithoutPartitionColumns.toList.map(_.getChildSchema.getTableName)
        }. Please drop the specified aggregate tables to continue")
      } else {
        val childDropPartitionCommands =
          parentCarbonTable.getTableInfo.getDataMapSchemaList.asScala.map { dataMapSchema =>
            val tableIdentifier = TableIdentifier(dataMapSchema.getChildSchema.getTableName,
              Some(parentCarbonTable.getDatabaseName))
            // as the aggregate table columns start with parent table name therefore the
            // partition column also has to be updated with parent table name to generate
            // partitionSpecs for the child table.
            val childSpecs = dropPartitionEvent.specs.map {
               spec => spec.map {
                case (key, value) => (s"${parentCarbonTable.getTableName}_$key", value)
              }
            }
            CarbonAlterTableDropHivePartitionCommand(
              tableIdentifier,
              childSpecs,
              dropPartitionEvent.ifExists,
              dropPartitionEvent.purge,
              dropPartitionEvent.retainData,
              operationContext)
        }
        operationContext.setProperty("dropPartitionCommands", childDropPartitionCommands)
        childDropPartitionCommands.foreach(_.processMetadata(SparkSession.getActiveSession.get))

      }
    } else if (parentCarbonTable.isChildDataMap) {
      if (operationContext.getProperty("isInternalDropCall") == null) {
        throw new UnsupportedOperationException("Cannot drop partition directly on aggregate table")
      }
    }
  }
}

/**
 * below class will be used to create load command for compaction
 * for all the pre agregate child data map
 */
object CompactionProcessMetaListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override protected def onEvent(event: Event,
      operationContext: OperationContext): Unit = {
    val sparkSession = SparkSession.getActiveSession.get
    val tableEvent = event.asInstanceOf[LoadMetadataEvent]
    val table = tableEvent.getCarbonTable
    if (!table.isChildDataMap && CarbonUtil.hasAggregationDataMap(table)) {
      val aggregationDataMapList = table.getTableInfo.getDataMapSchemaList.asScala
        .filter(_.isInstanceOf[AggregationDataMapSchema])
        .asInstanceOf[mutable.ArrayBuffer[AggregationDataMapSchema]]
      for (dataMapSchema: AggregationDataMapSchema <- aggregationDataMapList) {
        val childTableName = dataMapSchema.getRelationIdentifier.getTableName
        val childDatabaseName = dataMapSchema.getRelationIdentifier.getDatabaseName
        // Creating a new query string to insert data into pre-aggregate table from that same table.
        // For example: To compact preaggtable1 we can fire a query like insert into preaggtable1
        // select * from preaggtable1
        // The following code will generate the select query with a load UDF that will be used to
        // apply DataLoadingRules
        val childDataFrame = sparkSession.sql(new CarbonSpark2SqlParser()
          // adding the aggregation load UDF
          .addPreAggLoadFunction(
          // creating the select query on the bases on table schema
          PreAggregateUtil.createChildSelectQuery(
            dataMapSchema.getChildSchema, table.getDatabaseName))).drop("preAggLoad")
        val loadCommand = PreAggregateUtil.createLoadCommandForChild(
          dataMapSchema.getChildSchema.getListOfColumns,
          TableIdentifier(childTableName, Some(childDatabaseName)),
          childDataFrame,
          false,
          sparkSession)
        val uuid = Option(operationContext.getProperty("uuid")).
          getOrElse(UUID.randomUUID()).toString
        operationContext.setProperty("uuid", uuid)
        loadCommand.processMetadata(sparkSession)
        operationContext
          .setProperty(dataMapSchema.getChildSchema.getTableName + "_Compaction", loadCommand)
        loadCommand.operationContext = operationContext
      }
    } else if (table.isChildDataMap) {
      val childTableName = table.getTableName
      val childDatabaseName = table.getDatabaseName
      // Creating a new query string to insert data into pre-aggregate table from that same table.
      // For example: To compact preaggtable1 we can fire a query like insert into preaggtable1
      // select * from preaggtable1
      // The following code will generate the select query with a load UDF that will be used to
      // apply DataLoadingRules
      val childDataFrame = sparkSession.sql(new CarbonSpark2SqlParser()
        // adding the aggregation load UDF
        .addPreAggLoadFunction(
        // creating the select query on the bases on table schema
        PreAggregateUtil.createChildSelectQuery(
          table.getTableInfo.getFactTable, table.getDatabaseName))).drop("preAggLoad")
      val loadCommand = PreAggregateUtil.createLoadCommandForChild(
        table.getTableInfo.getFactTable.getListOfColumns,
        TableIdentifier(childTableName, Some(childDatabaseName)),
        childDataFrame,
        false,
        sparkSession)
      val uuid = Option(operationContext.getProperty("uuid")).getOrElse("").toString
      loadCommand.processMetadata(sparkSession)
      operationContext.setProperty(table.getTableName + "_Compaction", loadCommand)
      operationContext.setProperty("uuid", uuid)
      loadCommand.operationContext = operationContext
    }

  }
}

/**
 * Below class to is to create LoadCommand for loading the
 * the data of pre aggregate data map
 */
object LoadProcessMetaListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override protected def onEvent(event: Event,
      operationContext: OperationContext): Unit = {
    val sparkSession = SparkSession.getActiveSession.get
    val tableEvent = event.asInstanceOf[LoadMetadataEvent]
    if (!tableEvent.isCompaction) {
      val table = tableEvent.getCarbonTable
      if (CarbonUtil.hasAggregationDataMap(table)) {
        // getting all the aggergate datamap schema
        val aggregationDataMapList = table.getTableInfo.getDataMapSchemaList.asScala
          .filter(_.isInstanceOf[AggregationDataMapSchema])
          .asInstanceOf[mutable.ArrayBuffer[AggregationDataMapSchema]]
        // sorting the datamap for timeseries rollup
        val sortedList = aggregationDataMapList.sortBy(_.getOrdinal)
        val parentTableName = table.getTableName
        val databaseName = table.getDatabaseName
        // if the table is child then extract the uuid from the operation context and the parent
        // would already generated UUID.
        // if parent table then generate a new UUID else use empty.
        val uuid =
          Option(operationContext.getProperty("uuid")).getOrElse(UUID.randomUUID()).toString
        val list = scala.collection.mutable.ListBuffer.empty[AggregationDataMapSchema]
        for (dataMapSchema: AggregationDataMapSchema <- sortedList) {
          val childTableName = dataMapSchema.getRelationIdentifier.getTableName
          val childDatabaseName = dataMapSchema.getRelationIdentifier.getDatabaseName
          val childSelectQuery = if (!dataMapSchema.isTimeseriesDataMap) {
            (PreAggregateUtil.getChildQuery(dataMapSchema), "")
          } else {
            // for timeseries rollup policy
            val tableSelectedForRollup = PreAggregateUtil.getRollupDataMapNameForTimeSeries(list,
              dataMapSchema)
            list += dataMapSchema
            // if non of the rollup data map is selected hit the maintable and prepare query
            if (tableSelectedForRollup.isEmpty) {
              (PreAggregateUtil.createTimeSeriesSelectQueryFromMain(dataMapSchema.getChildSchema,
                parentTableName,
                databaseName), "")
            } else {
              // otherwise hit the select rollup datamap schema
              (PreAggregateUtil.createTimeseriesSelectQueryForRollup(dataMapSchema.getChildSchema,
                tableSelectedForRollup.get,
                databaseName),
                s"$databaseName.${tableSelectedForRollup.get.getChildSchema.getTableName}")
            }
          }
          val childDataFrame = sparkSession.sql(new CarbonSpark2SqlParser().addPreAggLoadFunction(
            childSelectQuery._1)).drop("preAggLoad")
          val isOverwrite =
            operationContext.getProperty("isOverwrite").asInstanceOf[Boolean]
          val loadCommand = PreAggregateUtil.createLoadCommandForChild(
            dataMapSchema.getChildSchema.getListOfColumns,
            TableIdentifier(childTableName, Some(childDatabaseName)),
            childDataFrame,
            isOverwrite,
            sparkSession,
            timeseriesParentTableName = childSelectQuery._2)
          operationContext.setProperty("uuid", uuid)
          loadCommand.operationContext.setProperty("uuid", uuid)
          loadCommand.processMetadata(sparkSession)
          operationContext.setProperty(dataMapSchema.getChildSchema.getTableName, loadCommand)
        }
      }
    }
  }
}
object LoadPostAggregateListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val carbonLoadModelOption =
      event match {
        case e: LoadTablePreStatusUpdateEvent => Some(e.getCarbonLoadModel)
        case e: LoadTablePostExecutionEvent => Some(e.getCarbonLoadModel)
        case _ => None
      }
    val sparkSession = SparkSession.getActiveSession.get
    if (carbonLoadModelOption.isDefined) {
      val carbonLoadModel = carbonLoadModelOption.get
      val table = carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable
      if (CarbonUtil.hasAggregationDataMap(table)) {
        val isOverwrite =
          operationContext.getProperty("isOverwrite").asInstanceOf[Boolean]
        if (isOverwrite && table.isHivePartitionTable) {
          val parentPartitionColumns = table.getPartitionInfo.getColumnSchemaList.asScala
            .map(_.getColumnName)
          val childTablesWithoutPartitionColumns =
            table.getTableInfo.getDataMapSchemaList.asScala.filter { dataMapSchema =>
              val childColumns = dataMapSchema.getChildSchema.getListOfColumns.asScala
              val partitionColExists = parentPartitionColumns.forall {
                partition =>
                  childColumns.exists { childColumn =>
                    childColumn.getAggFunction.isEmpty &&
                    childColumn.getParentColumnTableRelations.asScala.head.getColumnName.
                      equals(partition)
                  }
              }
              !partitionColExists
            }
          if (childTablesWithoutPartitionColumns.nonEmpty) {
            throw new MetadataProcessException(
              "Cannot execute load overwrite or insert overwrite as the following aggregate tables"
              + s" ${
                childTablesWithoutPartitionColumns.toList.map(_.getChildSchema.getTableName)
              } are not partitioned on all the partition column. Drop these to continue")
          }
        }
        // getting all the aggergate datamap schema
        val aggregationDataMapList = table.getTableInfo.getDataMapSchemaList.asScala
          .filter(_.isInstanceOf[AggregationDataMapSchema])
          .asInstanceOf[mutable.ArrayBuffer[AggregationDataMapSchema]]
        // sorting the datamap for timeseries rollup
        val sortedList = aggregationDataMapList.sortBy(_.getOrdinal)
        for (dataMapSchema: AggregationDataMapSchema <- sortedList) {
          val childLoadCommand = operationContext
            .getProperty(dataMapSchema.getChildSchema.getTableName)
            .asInstanceOf[CarbonLoadDataCommand]
          childLoadCommand.dataFrame = Some(PreAggregateUtil
            .getDataFrame(sparkSession, childLoadCommand.logicalPlan.get))
          childLoadCommand.operationContext = operationContext
          val timeseriesParent = childLoadCommand.internalOptions.get("timeseriesParent")
          val (parentTableIdentifier, segmentToLoad) =
            if (timeseriesParent.isDefined && timeseriesParent.get.nonEmpty) {
              val (parentTableDatabase, parentTableName) =
                (timeseriesParent.get.split('.')(0), timeseriesParent.get.split('.')(1))
              (TableIdentifier(parentTableName, Some(parentTableDatabase)),
                operationContext.getProperty(
                  s"${ parentTableDatabase }_${ parentTableName }_Segment").toString)
            } else {
              val currentSegmentFile = operationContext.getProperty("current.segmentfile")
              val segment = if (currentSegmentFile != null) {
                new Segment(carbonLoadModel.getSegmentId, currentSegmentFile.toString)
              } else {
                Segment.toSegment(carbonLoadModel.getSegmentId, null)
              }
              (TableIdentifier(table.getTableName, Some(table.getDatabaseName)), segment.toString)
            }

        PreAggregateUtil.startDataLoadForDataMap(
        parentTableIdentifier,
            segmentToLoad,
            validateSegments = false,
            childLoadCommand,
            isOverwrite,
            sparkSession)
        }
      }
    }
  }
}

/**
 * This listener is used to commit all the child data aggregate tables in one transaction. If one
 * failes all will be reverted to original state.
 */
object CommitPreAggregateListener extends OperationEventListener with CommitHelper {

  override protected def onEvent(event: Event,
      operationContext: OperationContext): Unit = {
    // The same listener is called for both compaction and load therefore getting the
    // carbonLoadModel from the appropriate event.
    val carbonLoadModel = event match {
      case loadEvent: LoadTablePostStatusUpdateEvent =>
        loadEvent.getCarbonLoadModel
      case compactionEvent: AlterTableCompactionPostStatusUpdateEvent =>
        compactionEvent.carbonLoadModel
    }
    val isCompactionFlow = Option(
      operationContext.getProperty("isCompaction")).getOrElse("false").toString.toBoolean
    val dataMapSchemas =
      carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable.getTableInfo.getDataMapSchemaList
      .asScala.filter(_.getChildSchema != null)
    // extract all child LoadCommands
    val childLoadCommands = if (!isCompactionFlow) {
      // If not compaction flow then the key for load commands will be tableName
        dataMapSchemas.map { dataMapSchema =>
          operationContext.getProperty(dataMapSchema.getChildSchema.getTableName)
            .asInstanceOf[CarbonLoadDataCommand]
        }
      } else {
      // If not compaction flow then the key for load commands will be tableName_Compaction
        dataMapSchemas.map { dataMapSchema =>
          operationContext.getProperty(dataMapSchema.getChildSchema.getTableName + "_Compaction")
            .asInstanceOf[CarbonLoadDataCommand]
        }
      }
     if (dataMapSchemas.nonEmpty) {
       val uuid = operationContext.getProperty("uuid").toString
      // keep committing until one fails
      val renamedDataMaps = childLoadCommands.takeWhile { childLoadCommand =>
        val childCarbonTable = childLoadCommand.table
        // Generate table status file name with UUID, forExample: tablestatus_1
        val oldTableSchemaPath = CarbonTablePath.getTableStatusFilePathWithUUID(
          childCarbonTable.getTablePath, uuid)
        // Generate table status file name without UUID, forExample: tablestatus
        val newTableSchemaPath = CarbonTablePath.getTableStatusFilePath(
          childCarbonTable.getTablePath)
        renameDataMapTableStatusFiles(oldTableSchemaPath, newTableSchemaPath, uuid)
      }
      // if true then the commit for one of the child tables has failed
      val commitFailed = renamedDataMaps.lengthCompare(dataMapSchemas.length) != 0
      if (commitFailed) {
        LOGGER.warn("Reverting table status file to original state")
        renamedDataMaps.foreach {
          loadCommand =>
            val carbonTable = loadCommand.table
            // rename the backup tablestatus i.e tablestatus_backup_UUID to tablestatus
            val backupTableSchemaPath =
              CarbonTablePath.getTableStatusFilePath(carbonTable.getTablePath) + "_backup_" + uuid
            val tableSchemaPath = CarbonTablePath.getTableStatusFilePath(carbonTable.getTablePath)
            markInProgressSegmentAsDeleted(backupTableSchemaPath, operationContext, carbonTable)
            renameDataMapTableStatusFiles(backupTableSchemaPath, tableSchemaPath, "")
        }
      }
      // after success/failure of commit delete all tablestatus files with UUID in their names.
      // if commit failed then remove the segment directory
      cleanUpStaleTableStatusFiles(childLoadCommands.map(_.table),
        operationContext,
        uuid)
      if (commitFailed) {
        sys.error("Failed to update table status for pre-aggregate table")
      }
    }


  }
}

/**
 * Listener to handle the operations that have to be done after compaction for a table has finished.
 */
object AlterPreAggregateTableCompactionPostListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val compactionEvent = event.asInstanceOf[AlterTableCompactionPreStatusUpdateEvent]
    val carbonTable = compactionEvent.carbonTable
    val compactionType = compactionEvent.carbonMergerMapping.campactionType
    if (compactionType == CompactionType.CUSTOM) {
      return
    }
    val carbonLoadModel = compactionEvent.carbonLoadModel
    val sparkSession = compactionEvent.sparkSession
    if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
      carbonTable.getTableInfo.getDataMapSchemaList.asScala.foreach { dataMapSchema =>
        val childRelationIdentifier = dataMapSchema.getRelationIdentifier
        val alterTableModel = AlterTableModel(Some(childRelationIdentifier.getDatabaseName),
          childRelationIdentifier.getTableName,
          None,
          compactionType.toString,
          Some(System.currentTimeMillis()),
          "")
        operationContext.setProperty(
          dataMapSchema.getRelationIdentifier.getDatabaseName + "_" +
          dataMapSchema.getRelationIdentifier.getTableName + "_Segment",
          carbonLoadModel.getSegmentId)
        CarbonAlterTableCompactionCommand(alterTableModel, operationContext = operationContext)
          .run(sparkSession)
      }
    }
  }
}

object LoadPreAggregateTablePreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val loadEvent = event.asInstanceOf[LoadTablePreExecutionEvent]
    val carbonLoadModel = loadEvent.getCarbonLoadModel
    val table = carbonLoadModel.getCarbonDataLoadSchema.getCarbonTable
    val isInternalLoadCall = carbonLoadModel.isAggLoadRequest
    if (table.isChildDataMap && !isInternalLoadCall) {
      throw new UnsupportedOperationException(
        "Cannot insert/load data directly into pre-aggregate table")
    }
  }
}

object PreAggregateDataTypeChangePreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val dataTypeChangePreListener = event.asInstanceOf[AlterTableDataTypeChangePreEvent]
    val carbonTable = dataTypeChangePreListener.carbonTable
    val alterTableDataTypeChangeModel = dataTypeChangePreListener.alterTableDataTypeChangeModel
    val columnToBeAltered: String = alterTableDataTypeChangeModel.columnName
    if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
      val dataMapSchemas = carbonTable.getTableInfo.getDataMapSchemaList
      dataMapSchemas.asScala.foreach { dataMapSchema =>
        val childColumns = dataMapSchema.getChildSchema.getListOfColumns
        val parentColumnNames = childColumns.asScala
          .flatMap(_.getParentColumnTableRelations.asScala.map(_.getColumnName))
        if (parentColumnNames.contains(columnToBeAltered)) {
          throw new UnsupportedOperationException(
            s"Column $columnToBeAltered exists in a pre-aggregate table. Drop pre-aggregate table" +
            "to continue")
        }
      }
    }
    if (carbonTable.isChildDataMap) {
      throw new UnsupportedOperationException(
        s"Cannot change data type for columns in pre-aggregate table ${ carbonTable.getDatabaseName
        }.${ carbonTable.getTableName }")
    }
  }
}

object PreAggregateAddColumnsPreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val dataTypeChangePreListener = event.asInstanceOf[AlterTableAddColumnPreEvent]
    val carbonTable = dataTypeChangePreListener.carbonTable
    if (carbonTable.isChildDataMap) {
      throw new UnsupportedOperationException(
        s"Cannot add columns in pre-aggreagate table ${ carbonTable.getDatabaseName
        }.${ carbonTable.getTableName }")
    }
  }
}

object PreAggregateDeleteSegmentByDatePreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val deleteSegmentByDatePreEvent = event.asInstanceOf[DeleteSegmentByDatePreEvent]
    val carbonTable = deleteSegmentByDatePreEvent.carbonTable
    if (carbonTable != null) {
      if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
        throw new UnsupportedOperationException(
          "Delete segment operation is not supported on tables which have a pre-aggregate table. " +
          "Drop pre-aggregation table to continue")
      }
      if (carbonTable.isChildDataMap) {
        throw new UnsupportedOperationException(
          "Delete segment operation is not supported on pre-aggregate table")
      }
    }
  }
}

object PreAggregateDeleteSegmentByIdPreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val tableEvent = event.asInstanceOf[DeleteSegmentByIdPreEvent]
    val carbonTable = tableEvent.carbonTable
    if (carbonTable != null) {
      if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
        throw new UnsupportedOperationException(
          "Delete segment operation is not supported on tables which have a pre-aggregate table")
      }
      if (carbonTable.isChildDataMap) {
        throw new UnsupportedOperationException(
          "Delete segment operation is not supported on pre-aggregate table")
      }
    }
  }

}

object PreAggregateDropColumnPreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val dataTypeChangePreListener = event.asInstanceOf[AlterTableDropColumnPreEvent]
    val carbonTable = dataTypeChangePreListener.carbonTable
    val alterTableDropColumnModel = dataTypeChangePreListener.alterTableDropColumnModel
    val columnsToBeDropped = alterTableDropColumnModel.columns
    if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
      val dataMapSchemas = carbonTable.getTableInfo.getDataMapSchemaList
      dataMapSchemas.asScala.foreach { dataMapSchema =>
        val parentColumnNames = dataMapSchema.getChildSchema.getListOfColumns.asScala
          .flatMap(_.getParentColumnTableRelations.asScala.map(_.getColumnName))
        val columnExistsInChild = parentColumnNames.collectFirst {
          case parentColumnName if columnsToBeDropped.contains(parentColumnName) =>
            parentColumnName
        }
        if (columnExistsInChild.isDefined) {
          throw new UnsupportedOperationException(
            s"Column ${ columnExistsInChild.head } cannot be dropped because it exists in a " +
            s"pre-aggregate table ${ dataMapSchema.getRelationIdentifier.toString }")
        }
      }
    }
    if (carbonTable.isChildDataMap) {
      throw new UnsupportedOperationException(s"Cannot drop columns in pre-aggreagate table ${
        carbonTable.getDatabaseName}.${ carbonTable.getTableName }")
    }
  }
}

object PreAggregateRenameTablePreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event,
      operationContext: OperationContext): Unit = {
    val renameTablePostListener = event.asInstanceOf[AlterTableRenamePreEvent]
    val carbonTable = renameTablePostListener.carbonTable
    if (carbonTable.isChildDataMap) {
      throw new UnsupportedOperationException(
        "Rename operation for pre-aggregate table is not supported.")
    }
    if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
      throw new UnsupportedOperationException(
        "Rename operation is not supported for table with pre-aggregate tables")
    }
  }
}

object UpdatePreAggregatePreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val tableEvent = event.asInstanceOf[UpdateTablePreEvent]
    val carbonTable = tableEvent.carbonTable
    if (carbonTable != null) {
      if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
        throw new UnsupportedOperationException(
          "Update operation is not supported for tables which have a pre-aggregate table. Drop " +
          "pre-aggregate tables to continue.")
      }
      if (carbonTable.isChildDataMap) {
        throw new UnsupportedOperationException(
          "Update operation is not supported for pre-aggregate table")
      }
    }
  }
}

object DeletePreAggregatePreListener extends OperationEventListener {
  /**
   * Called on a specified event occurrence
   *
   * @param event
   * @param operationContext
   */
  override def onEvent(event: Event, operationContext: OperationContext): Unit = {
    val tableEvent = event.asInstanceOf[DeleteFromTablePreEvent]
    val carbonTable = tableEvent.carbonTable
    if (carbonTable != null) {
      if (CarbonUtil.hasAggregationDataMap(carbonTable)) {
        throw new UnsupportedOperationException(
          "Delete operation is not supported for tables which have a pre-aggregate table. Drop " +
          "pre-aggregate tables to continue.")
      }
      if (carbonTable.isChildDataMap) {
        throw new UnsupportedOperationException(
          "Delete operation is not supported for pre-aggregate table")
      }
    }
  }
}
