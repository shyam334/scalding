package com.twitter.scalding_internal.db.vertica

import cascading.flow.FlowDef

import com.twitter.scalding._
import com.twitter.scalding_internal.db._
import com.twitter.scalding_internal.db.jdbc._

import cascading.tuple.Fields
import cascading.tap.Tap
import cascading.scheme.Scheme
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.OutputCollector
import org.apache.hadoop.mapred.RecordReader
import cascading.tap.SinkMode
import cascading.scheme.hadoop.{ TextDelimited, SequenceFile }

import scala.util.{ Try, Success, Failure }

object VerticaSink {
  def apply[T: DBTypeDescriptor](database: Database,
    tableName: TableName,
    schema: SchemaName)(implicit dbsInEnv: AvailableDatabases): VerticaSink[T] =
    VerticaSink[T](dbsInEnv(database), tableName, schema)
}

case class VerticaSink[T: DBTypeDescriptor](
  connectionConfig: ConnectionConfig,
  tableName: TableName,
  schema: SchemaName)(implicit dbsInEnv: AvailableDatabases) extends Source with TypedSink[T] with JDBCLoadOptions {

  private val jdbcTypeInfo = implicitly[DBTypeDescriptor[T]]

  val columns = jdbcTypeInfo.columnDefn.columns

  override def sinkFields = jdbcTypeInfo.fields

  override def setter[U <: T] = TupleSetter.asSubSetter[T, U](jdbcTypeInfo.setter)

  @transient val verticaWriter = new VerticaJdbcWriter(
    tableName,
    schema,
    connectionConfig,
    columns,
    AdditionalQueries(preloadQuery, postloadQuery))

  @transient val completionHandler = new JdbcSinkCompletionHandler(verticaWriter)

  /** The scheme to use if the source is on hdfs. */
  def hdfsScheme: Scheme[JobConf, RecordReader[_, _], OutputCollector[_, _], _, _] =
    HadoopSchemeInstance(new TextDelimited(sinkFields,
      null, false, false, "\t", true, "\"", sinkFields.getTypesClasses, true).asInstanceOf[cascading.scheme.Scheme[_, _, _, _, _]])

  val sinkMode: SinkMode = SinkMode.REPLACE

  override def createTap(readOrWrite: AccessMode)(implicit mode: Mode): Tap[_, _, _] = {
    mode match {
      // TODO support strict in Local
      case Local(_) => sys.error("Local mode not supported for the VerticaSink")
      case Hdfs(_, conf) => readOrWrite match {
        case Read => sys.error("Read mode not supported VerticaSink")
        case Write => CastHfsTap(new VerticaSinkTap(hdfsScheme, new JobConf(conf), sinkMode, completionHandler))
      }
      case _ => {
        val tryTtp = Try(TestTapFactory(this, hdfsScheme, sinkMode)).map {
          // these java types are invariant, so we cast here
          _.createTap(readOrWrite)
            .asInstanceOf[Tap[Any, Any, Any]]
        }

        tryTtp match {
          case Success(s) => s
          case Failure(e) => throw new java.lang.IllegalArgumentException(s"Failed to create tap for: $toString, with error: ${e.getMessage}", e)
        }
      }
    }
  }

}
