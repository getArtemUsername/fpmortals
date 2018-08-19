// Copyright: 2017 - 2018 Sam Halliday
// License: http://www.gnu.org/licenses/gpl-3.0.en.html

package fommil
package dda

import prelude._, Z._

import scala.collection.immutable.List

import pureconfig.orphans._
import scalaz.ioeffect.console._

import algebra._
import logic._
import interpreters._
import http._
import http.interpreters._
import http.oauth2._
import http.oauth2.interpreters._
import time._

object Main extends SafeApp {

  def run(args: List[String]): IO[Void, ExitStatus] = {
    if (args.contains("--machines")) auth("machines")
    else if (args.contains("--drone")) auth("drone")
    else agents(BearerToken("<invalid>", Epoch(0)))
  }.attempt[Void].map {
    case \/-(_) => ExitStatus.ExitNow(0)
    case -\/(err) =>
      java.lang.System.err.println(err)
      ExitStatus.ExitNow(1)
  }

  // performs the OAuth 2.0 dance to obtain refresh tokens
  def auth(name: String): Task[Unit] = {
    type HT[f[_], a] = EitherT[f, JsonClient.Error, a]
    type H[a]        = HT[Task, a]

    for {
      config    <- readConfig[ServerConfig](name + ".server").liftM[HT]
      ui        <- BlazeUserInteraction().liftM[HT]
      auth      = new AuthModule(config)(ui)
      codetoken <- auth.authenticate.liftM[HT]
      clock     = LocalClock.liftM[Task, HT](new LocalClockTask)
      client    <- BlazeJsonClient[H].liftM[HT]
      access    = new AccessModule(config)(client, clock)
      token     <- access.access(codetoken)
      _         <- putStrLn(z"got token: ${token._1}").toTask.liftM[HT]
    } yield ()
  }.run.swallowError

  // runs the app, requires that refresh tokens are provided
  def agents(bearer: BearerToken): Task[Unit] = {
    type HT[f[_], a] = EitherT[f, JsonClient.Error, a]
    type GT[f[_], a] = StateT[f, BearerToken, a]
    type H[a]        = HT[Task, a]
    type G[a]        = GT[H, a]

    for {
      config <- readConfig[AppConfig]
      blaze  <- BlazeJsonClient[G]
      _ <- {
        val T: LocalClock[G] = {
          import LocalClock.liftM
          liftM(liftM(new LocalClockTask))
        }
        val D: Drone[G] = new DroneModule(oauth(config.drone)(blaze))
        val M: Machines[G] =
          new GoogleMachinesModule(oauth(config.machines)(blaze, T))
        val agents: DynAgents[G] = new DynAgentsModule(D, M)
        for {
          start <- agents.initial
          _ <- {
            type FT[f[_], a] = StateT[f, WorldView, a]
            type F[a]        = FT[G, a]
            val F: MonadState[F, WorldView] = MonadState[F, WorldView]
            val A: DynAgents[F]             = DynAgents.liftM(agents)
            val S: Sleep[F] = {
              import Sleep.liftM
              liftM(liftM(liftM(new SleepTask)))
            }
            step(F, A, S).forever[Unit]
          }.run(start)
        } yield ()
      }.eval(bearer).run.swallowError
    } yield ()
  }

  private def step[F[_]](
    implicit
    F: MonadState[F, WorldView],
    A: DynAgents[F],
    S: Sleep[F]
  ): F[Unit] =
    for {
      old     <- F.get
      updated <- A.update(old)
      changed <- A.act(updated)
      _       <- F.put(changed)
      _       <- S.sleep(10.seconds)
    } yield ()

  private def oauth[M[_]](
    config: OAuth2Config
  )(
    H: JsonClient[M],
    T: LocalClock[M]
  )(
    implicit
    M: MonadError[M, JsonClient.Error],
    S: MonadState[M, BearerToken]
  ): OAuth2JsonClient[M] =
    new OAuth2JsonClientModule[M](config.token)(
      H,
      T,
      new RefreshModule(config.server)(H, T)(M)
    )

  private def oauth[M[_]: Monad](
    bearer: BearerToken
  )(
    H: JsonClient[M]
  ): OAuth2JsonClient[M] =
    new BearerJsonClientModule[M](bearer)(H)

  @deriving(ConfigReader)
  final case class OAuth2Config(
    token: RefreshToken,
    server: ServerConfig
  )
  @deriving(ConfigReader)
  final case class AppConfig(
    drone: BearerToken,
    machines: OAuth2Config
  )
}