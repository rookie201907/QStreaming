package com.qiniu.stream.spark.source

import com.qiniu.stream.spark.config.RichSchema._
import com.qiniu.stream.spark.config.{RowFormat, RowTime, SourceTable}
import com.qiniu.stream.spark.listener.KafkaLagListener
import com.qiniu.stream.spark.util.Regex2Json
import com.qiniu.stream.util.Logging
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.{DataFrame, SparkSession, functions => F}

/**
  *
  * df.printSchema() reveals the schema of our DataFrame.
  * before convert
  * root
  * |-- key: binary (nullable = true)
  * |-- value: binary (nullable = true)
  * |-- topic: string (nullable = true)
  * |-- partition: integer (nullable = true)
  * |-- offset: long (nullable = true)
  * |-- timestamp: timestamp (nullable = true)
  * |-- timestampType: integer (nullable = true)
  *
  * after convert
  * kafka (原始的kafka结构)
  * |-- key: binary (nullable = true)
  * |-- topic: string (nullable = true)
  * |-- partition: integer (nullable = true)
  * |-- offset: long (nullable = true)
  * |-- timestamp: timestamp (nullable = true)
  * |-- timestampType: integer (nullable = true)
  * data (是json打散后的)
  *
  */
class StreamReader extends Reader with WaterMarker with Logging {


  private def jsonTable(table: DataFrame,sourceTable: SourceTable) = {
    val kafkaFields = table.schema.fieldNames.filterNot(_ == "value")
    table.withColumn("value", table.col("value").cast(DataTypes.StringType))
      .withColumn("value", F.from_json(F.col("value"), schema = sourceTable.schema.get.structType))
      .select("value.*", kafkaFields: _*)

  }

  private def avroTable(avroFormat: RowFormat, table: DataFrame) = {
    import org.apache.spark.sql.avro._
    val kafkaFields = table.schema.fieldNames.filterNot(_ == "value")
    require(avroFormat.props.get("jsonSchema").isDefined, "jsonSchema is required for avro row format")
    val jsonSchema = avroFormat.props("jsonSchema")
    table.withColumn("value", from_avro(table.col("value"), jsonSchema)).select("value.*", kafkaFields: _*)

  }

  private def csvTable(csvFormat: RowFormat, table: DataFrame) = {
    table.withColumn("value", table.col("value").cast(DataTypes.StringType))
  }

  override def read(sparkSession: SparkSession,sourceTable: SourceTable): DataFrame = {
    require(sourceTable.schema.isDefined, "schema  is required")
    var table = sparkSession.readStream.format(sourceTable.connector.name).options(sourceTable.connector.options).load()
    enableKafkaLagListener(sparkSession,sourceTable)
    table = sourceTable.format match {
      case format if format.isJsonFormat =>
        jsonTable(table,sourceTable)
      case format if format.isAvroFormat =>
        avroTable(format, table)
      case format if format.isCsvFormat =>
        csvTable(format, table)
      case format if format.isRegExFormat=>
        regexTable(sparkSession,table, sourceTable)
      case format if format.isTextFormat=>
        table
      case _ =>
        jsonTable(table,sourceTable)
    }

    table = sourceTable.schema.get.timeField match {
      case Some(rowTime: RowTime) =>
        withWaterMark(table, Some(rowTime))
      case _ => table
    }
    table
  }


  private def regexTable(sparkSession: SparkSession,table:DataFrame,sourceTable: SourceTable) = {
    val regexPattern = sourceTable.format.props.get("pattern")
    require(regexPattern.isDefined, "regex pattern is required")
    val ddl = sourceTable.schema.get.toDDL
    val structType = sourceTable.schema.get.structType
    val pattern = regexPattern.get
    sparkSession.udf.register("regex2Json", F.udf((line: String) => {
      Regex2Json.toJson(line, pattern, ddl)
    }))
    val rawFields = List("key", "partition", "offset", "timestamp", "timestampType", "topic")
    table.withColumn("kafkaValue", F.struct(rawFields .map(F.col): _*))
      .selectExpr("regex2json(CAST(value AS STRING)) as jsonValue", "kafkaValue")
      .withColumn("value", F.from_json(F.col("jsonValue"), schema = structType))
      .select("value.*", "kafkaValue")
  }

  private def enableKafkaLagListener(sparkSession: SparkSession,sourceTable: SourceTable): Unit = {
    sourceTable.connector.option("group_id").foreach(groupId => {
      val bootStrapServer = sourceTable.connector.option("kafka.bootstrap.servers")
      require(bootStrapServer.isDefined)
      log.info("register streaming query listener for kafka streaming")
      sparkSession.streams.addListener(new KafkaLagListener(groupId, bootStrapServer.get))
    })
  }
}