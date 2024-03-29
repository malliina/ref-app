package com.malliina.app.db

import cats.effect.Async
import cats.effect.kernel.Resource
import com.malliina.app.db.DoobieDatabase.log
import com.malliina.util.AppLogger
import com.zaxxer.hikari.HikariConfig
import doobie.free.connection.ConnectionIO
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import doobie.util.log.{ExecFailure, LogHandler, ProcessingFailure, Success}
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

import scala.concurrent.duration.DurationInt

object DoobieDatabase:
  private val log = AppLogger(getClass)

  def default[F[_]: Async](conf: DatabaseConf): Resource[F, DatabaseRunner[F]] =
    migratedResource(conf).map { tx => DoobieDatabase(tx) }

  private def migratedResource[F[_]: Async](conf: DatabaseConf): Resource[F, HikariTransactor[F]] =
    Resource.pure(migrate(conf)).flatMap { _ => resource(hikariConf(conf)) }

  private def resource[F[_]: Async](conf: HikariConfig): Resource[F, HikariTransactor[F]] =
    for
      ec <- ExecutionContexts.fixedThreadPool[F](32)
      tx <- HikariTransactor.fromHikariConfig[F](conf, ec)
    yield tx

  private def migrate(conf: DatabaseConf): MigrateResult =
    val flyway = Flyway.configure.dataSource(conf.url, conf.user, conf.pass).load()
    flyway.migrate()

  private def hikariConf(conf: DatabaseConf): HikariConfig =
    val hikari = new HikariConfig()
    hikari.setDriverClassName(DatabaseConf.MySQLDriver)
    hikari.setJdbcUrl(conf.url)
    hikari.setUsername(conf.user)
    hikari.setPassword(conf.pass)
    hikari.setMaxLifetime(60.seconds.toMillis)
    hikari.setMaximumPoolSize(10)
    log.info(s"Connecting to '${conf.url}'...")
    hikari

class DoobieDatabase[F[_]: Async](tx: HikariTransactor[F]) extends DatabaseRunner[F]:
  implicit val logHandler: LogHandler = LogHandler {
    case Success(sql, args, exec, processing) =>
      log.info(s"OK '$sql' exec ${exec.toMillis} ms processing ${processing.toMillis} ms.")
    case ProcessingFailure(sql, args, exec, processing, failure) =>
      log.error(s"Failed '$sql' in ${exec + processing}.", failure)
    case ExecFailure(sql, args, exec, failure) =>
      log.error(s"Exec failed '$sql' in $exec.'", failure)
  }

  def run[T](io: ConnectionIO[T]): F[T] = io.transact(tx)

trait DatabaseRunner[F[_]]:
  def logHandler: LogHandler
  def run[T](io: ConnectionIO[T]): F[T]
