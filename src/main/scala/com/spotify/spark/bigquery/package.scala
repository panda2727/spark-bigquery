/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.spark

import com.databricks.spark.avro._
import com.google.api.services.bigquery.model.TableReference
import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem
import com.google.cloud.hadoop.io.bigquery._
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.mapreduce.InputFormat
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

package object bigquery {

  implicit class BigQuerySQLContext(self: SQLContext) {

    val sc = self.sparkContext
    val conf = sc.hadoopConfiguration
    val bq = new BigQueryClient(conf)

    // Register GCS implementation
    if (conf.get("fs.gs.impl") == null) {
      conf.set("fs.gs.impl", classOf[GoogleHadoopFileSystem].getName)
    }

    def setBigQueryProjectId(projectId: String): Unit = {
      conf.set(BigQueryConfiguration.PROJECT_ID_KEY, projectId)

      // Also set project ID for GCS connector
      if (conf.get("fs.gs.project.id") == null) {
        conf.set("fs.gs.project.id", projectId)
      }
    }

    def setBigQueryGcsBucket(gcsBucket: String): Unit =
      conf.set(BigQueryConfiguration.GCS_BUCKET_KEY, gcsBucket)

    def bigQuerySelect(sqlQuery: String): DataFrame = bigQueryTable(bq.query(sqlQuery))

    def bigQueryTable(tableRef: TableReference): DataFrame = {
      conf.setClass(
        AbstractBigQueryInputFormat.INPUT_FORMAT_CLASS_KEY,
        classOf[AvroBigQueryInputFormat], classOf[InputFormat[LongWritable, GenericData.Record]])

      BigQueryConfiguration.configureBigQueryInput(
        conf, tableRef.getProjectId, tableRef.getDatasetId, tableRef.getTableId)

      val fClass = classOf[AvroBigQueryInputFormat]
      val kClass = classOf[LongWritable]
      val vClass = classOf[GenericData.Record]
      val rdd = sc
        .newAPIHadoopRDD(conf, fClass, kClass, vClass)
        .map(_._2)
      val schemaString = rdd.map(_.getSchema.toString).first()
      val schema = new Schema.Parser().parse(schemaString)

      val structType = SchemaConverters.toSqlType(schema).dataType.asInstanceOf[StructType]
      val converter = SchemaConverters.createConverterToSQL(schema)
        .asInstanceOf[GenericData.Record => Row]
      self.createDataFrame(rdd.map(converter), structType)
    }

    def bigQueryTable(tableSpec: String): DataFrame =
      bigQueryTable(BigQueryStrings.parseTableReference(tableSpec))

  }

}
