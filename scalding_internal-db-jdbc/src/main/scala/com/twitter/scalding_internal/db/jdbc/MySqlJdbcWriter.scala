/*
Copyright 2015 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.scalding_internal.db.jdbc

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{ FileSystem, Path }
import org.apache.hadoop.mapred.JobConf
import org.json4s._
import org.json4s.native.JsonMethods._
import org.slf4j.LoggerFactory

import com.twitter.scalding_internal.db._
import com.twitter.scalding_internal.db.jdbc.driver.DriverClass

import java.sql._
import scala.annotation.tailrec
import scala.io.Source
import scala.util.Try

class MySqlJdbcWriter[T](
  tableName: TableName,
  connectionConfig: ConnectionConfig,
  columns: Iterable[ColumnDefinition],
  batchSize: Int,
  replaceOnInsert: Boolean,
  addlQueries: AdditionalQueries)(json2CaseClass: String => T, jdbcSetter: JdbcStatementSetter[T])
  extends JdbcWriter(tableName, None, connectionConfig, columns, addlQueries) {

  import CloseableHelper._
  import TryHelper._

  private val log = LoggerFactory.getLogger(this.getClass)

  protected[this] val driverClassName = DriverClass("com.mysql.jdbc.Driver")

  def load(hadoopUri: HadoopUri, conf: JobConf): Try[Int] = {
    val insertStmt = s"""
    |INSERT INTO ${tableName.toStr} (${columns.map(_.name.toStr).mkString(",")})
    |VALUES (${Stream.continually("?").take(columns.size).mkString(",")})
    """.stripMargin('|')

    val query = if (replaceOnInsert)
      s"""
        |$insertStmt
        |ON DUPLICATE KEY UPDATE ${columns.map(_.name.toStr).map(c => s"$c=VALUES($c)").mkString(",")}
        """.stripMargin('|')
    else
      insertStmt

    log.info(s"Preparing to write from $hadoopUri to jdbc: $query")
    for {
      conn <- jdbcConnection
      ps <- Try(conn.prepareStatement(query)).onFailure(conn.closeQuietly())
      fs = FileSystem.newInstance(conf)
      files <- dataFiles(hadoopUri, fs).onFailure(ps.closeQuietly())
      loadCmds: Iterable[Try[Unit]] = files.map(processDataFile(_, fs, ps))
      count <- Try {
        // load files one at a time
        loadCmds.map(_.get) // throw if any file load fails
        val c = Try(ps.getUpdateCount).getOrElse(-1) // don't fail if just getting counts fails, default instead
        conn.commit()
        c
      }
        .onFailure { conn.rollback() }
        .onComplete {
          ps.closeQuietly()
          conn.closeQuietly()
          fs.closeQuietly()
        }
    } yield count
  }

  private def dataFiles(uri: HadoopUri, fs: FileSystem): Try[Iterable[Path]] = Try {
    fs.listStatus(new Path(uri.toStr))
      .map(_.getPath)
      .filter(_.getName != "_SUCCESS")
  }

  private def loadData(currentBatchCount: Int, it: Iterator[String], ps: PreparedStatement): Try[Unit] =
    (currentBatchCount, it.hasNext) match {
      case (c, true) if c == batchSize => Try(ps.executeBatch()).map(_ => loadData(0, it, ps))
      case (c, false) if c > 0 => Try(ps.executeBatch()) // end of data
      case (c, true) => // append to current batch
        for {
          rec <- Try(json2CaseClass(it.next))
          _ <- jdbcSetter(rec, ps)
          _ <- Try(ps.addBatch())
        } yield loadData(currentBatchCount + 1, it, ps)
      case _ => Try(())
    }

  private def processDataFile(p: Path, fs: FileSystem, ps: PreparedStatement): Try[Unit] =
    for {
      reader <- Try(Source.fromInputStream(fs.open(p)))
      _ <- loadData(0, reader.getLines(), ps).onComplete(reader.close())
    } yield ()
}
