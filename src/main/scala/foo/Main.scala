package foo

import java.util.concurrent.ConcurrentHashMap

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO, LiftIO, Sync, Timer}
import cats.implicits._
import cats.tagless.{FunctorK, InvariantK}
import cats.{Applicative, Functor, Monad, MonadError, ~>}
import tofu.{Raise, Timeout}

import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object Main extends App {

  final case class RecipeId(v: String) extends AnyVal
  final case class RecipeName(v: String) extends AnyVal
  final case class Recipe(id: RecipeId, name: RecipeName)

  val someRecipeId = RecipeId("foo")
  val someRecipe = Recipe(someRecipeId, RecipeName("bar"))

  class LegacyDao(implicit ec: ExecutionContext) extends Dao[Future] {
    private val map = new ConcurrentHashMap[RecipeId, Recipe]

    def save(r: Recipe): Future[Unit] = Future(map.put(r.id, r)).void
    def getById(id: RecipeId): Future[Option[Recipe]] =
      Future(Option(map.get(id)))
  }

  trait Printer[F[_]] {
    def printLine(s: String): F[Unit]
  }

  final class NotLegacyAtAllPrinter[F[_]](implicit F: Sync[F])
      extends Printer[F] {
    def printLine(s: String): F[Unit] = F.delay(println(s))
  }

  object Printer {
    implicit val functorK: FunctorK[Printer] =
      cats.tagless.Derive.functorK[Printer]
  }
  // Part 1. Refactor to lazy

  //The easies way ;)
  //type F[T] = Future[T]

  //Incorrect way

  class EasyAndStillLegacyDao(ld: LegacyDao)(implicit cs: ContextShift[IO]) {
    def save(r: Recipe): IO[Unit] = IO.fromFuture(IO.delay(ld.save(r)))
    def getById(id: RecipeId): IO[Option[Recipe]] =
      IO.fromFuture(IO.delay(ld.getById(id)))
  }

  class StillLegacyToBeFairDao {
    private val map = new ConcurrentHashMap[RecipeId, Recipe]

    def save(r: Recipe): IO[Unit] = IO.delay(map.put(r.id, r)).void
    def getById(id: RecipeId): IO[Option[Recipe]] =
      IO.delay(Option(map.get(id)))
  }

  //The correct way

  trait Dao[F[_]] {
    def save(r: Recipe): F[Unit]
    def getById(id: RecipeId): F[Option[Recipe]]
  }

  //possible to user @autoFunctorK for top-level objects
  object Dao {
    implicit val functorK: FunctorK[Dao] = cats.tagless.Derive.functorK[Dao]
    implicit val invK: InvariantK[Dao] = cats.tagless.Derive.invariantK[Dao]
  }

  // write once section -->
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val cs = cats.effect.IO.contextShift(global)

  val futureToIO = new ~>[Future, IO] {
    def apply[A](fa: Future[A]): IO[A] = IO.fromFuture(IO.delay(fa))(cs)
  }
  import cats.tagless.implicits._
  // <-- write once section

  val legacyInstance: Dao[Future] = new LegacyDao()

  val notLegacyInstance: Dao[IO] = legacyInstance.mapK(futureToIO)
  val printer: Printer[IO] = new NotLegacyAtAllPrinter[IO]

  def program[F[_]: Monad](dao: Dao[F], printer: Printer[F]): F[Unit] = {
    dao.save(someRecipe) >> dao
      .getById(someRecipeId)
      .flatMap(r => printer.printLine(r.toString))
  }

  program(notLegacyInstance, printer).unsafeRunSync()

  // look, ma, I'm lazy

  // Part 2. Error Handling

  // General opinion: business services should have well typed errors
  // dao, http clients and end points like this are usually fine with exceptions
  // if they bear no business value i.e. if you're not going to handle error, hell with types
  // if you're fine with 500 Internal Server Error if database is down, don't make it typed
  sealed trait DaoError extends Product with Serializable
  object DaoError {
    case object AlreadyExists extends DaoError

    def alreadyExists: DaoError = AlreadyExists
  }

  // write once section -->
  type FEitherErr[F[_], Err, T] = F[Either[Err, T]]

  implicit final class FErrSyntax[F[_], T](val v: F[T]) extends AnyVal {
    def liftF[Err](implicit F: Applicative[F]): FEitherErr[F, Err, T] =
      v.map(_.asRight[Err])
  }

  implicit def fErrMonad[F[_], Err](implicit F: Monad[F]) =
    new Monad[FEitherErr[F, Err, *]] {
      def pure[A](x: A): FEitherErr[F, Err, A] = x.asRight[Err].pure[F]

      def flatMap[A, B](fa: FEitherErr[F, Err, A])(
          f: A => FEitherErr[F, Err, B]): FEitherErr[F, Err, B] =
        F.flatMap[Either[Err, A], Either[Err, B]](fa)(_.flatTraverse(f))

      def tailRecM[A, B](a: A)(
          f: A => FEitherErr[F, Err, Either[A, B]]): FEitherErr[F, Err, B] =
        F.flatMap(f(a)) {
          case Left(s)         => F.pure(Left(s))
          case Right(Left(aa)) => tailRecM(aa)(f)
          case Right(Right(b)) => F.pure(Right(b))
        }
    }

  implicit def fErrRaise[F[_], Err](
      implicit F: Monad[F]): Raise[FEitherErr[F, Err, *], Err] =
    new Raise[FEitherErr[F, Err, *], Err] {
      override def raise[A](err: Err): FEitherErr[F, Err, A] =
        F.pure(err.asLeft[A])
    }

  def liftF[F[_], Err](implicit F: Functor[F]): ~>[F, FEitherErr[F, Err, *]] =
    new ~>[F, FEitherErr[F, Err, *]] {
      def apply[A](fa: F[A]): FEitherErr[F, Err, A] = fa.map(_.asRight[Err])
    }

  implicit final class FErrSyntax1[M[_[_]], F[_]](val v: M[F]) extends AnyVal {
    def liftFErr[Err](implicit F: Functor[F],
                      FK: FunctorK[M]): M[FEitherErr[F, Err, *]] = {
      v.mapK(liftF[F, Err])
    }
  }

  implicit def liftIO[F[_]: LiftIO, Err]: LiftIO[FEitherErr[F, Err, *]] =
    new LiftIO[FEitherErr[F, Err, *]] {
      def liftIO[A](ioa: IO[A]): FEitherErr[F, Err, A] =
        ioa.map(_.asRight[Err]).to[F]
    }
  // <-- write once section

  type DaoF[T] = FEitherErr[IO, DaoError, T]

  final class ErrorTypedDaoImpl[F[_]: Monad: LiftIO](
      ref: Ref[IO, Map[RecipeId, Recipe]])(implicit R: Raise[F, DaoError])
      extends Dao[F] {
    def save(r: Recipe): F[Unit] = getById(r.id).flatMap { x =>
      if (x.isEmpty) {
        ref.modify(_.updated(r.id, r) -> ()).to[F]
      } else {
        R.raise(DaoError.alreadyExists)
      }
    }

    def getById(id: RecipeId): F[Option[Recipe]] = ref.get.map(_.get(id)).to[F]
  }

  val typedErrInstance =
    Ref
      .of[IO, Map[RecipeId, Recipe]](Map.empty)
      .map(new ErrorTypedDaoImpl[DaoF](_))

  typedErrInstance
    .flatMap(
      dao =>
        program(dao, printer.liftFErr[DaoError]) >> dao
          .save(someRecipe)
          .flatMap(r => printer.printLine(r.toString)))
    .unsafeRunSync()
  // Part 2.1 less typed approach
  // throwable based hierarchy
  // go to https://github.com/daron666/errorness

  // Part 3 case study timeouts

  import scala.concurrent.duration._

  def makeTimeout[F[_]: Timer: Monad] = new ~>[F, F] {
    def apply[A](fa: F[A]): F[A] = Timer[F].sleep(1 second) >> fa
  }

  def handleTimeout[M[_[_]]: FunctorK, F[_]: Timeout](v: M[F])(implicit M: MonadError[F, Throwable]): M[F] = {
    val timeout = new ~>[F, F] {
      def apply[A](fa: F[A]): F[A] = Timeout[F].timeoutTo(fa, 0.5.seconds, M.raiseError(new RuntimeException("Bang!")))
    }
    v.mapK(timeout)
  }
  implicit val timerIO = cats.effect.IO.timer(global)
  val longRunningInstance = notLegacyInstance.mapK(makeTimeout[IO])

  program(longRunningInstance, printer).unsafeRunAsyncAndForget()

  val timeoutingInstance = handleTimeout(longRunningInstance)

  program(timeoutingInstance, printer).attempt.flatMap(r => printer.printLine(r.toString)).unsafeRunAsyncAndForget()

  // Part 3 case study retries

  import tofu.syntax.monadError._

  def handleRetry[M[_[_]]: FunctorK, F[_]](v: M[F])(implicit M: MonadError[F, Throwable]): M[F] = {
    val retry = new ~>[F, F] {
      def apply[A](fa: F[A]): F[A] = {
        fa.retry(5)
      }
    }
    v.mapK(retry)
  }

  val retriedInstance = handleRetry(timeoutingInstance)

  program(retriedInstance, printer).attempt.flatMap(r => printer.printLine(r.toString)).unsafeRunSync()

  // Part 4 case study logging
  import tofu.logging._

  def logEveryCall[M[_[_]]: FunctorK, F[_]: Sync](v: M[F])(implicit tag: ClassTag[M[Any]]): M[F] = {
    val log = new ~>[F, F] {
      def apply[A](fa: F[A]): F[A] = {
        implicit val anyLoggable: Loggable[A] = new DictLoggable[A] {
          def logShow(a: A): String = a.toString

          def fields[I, V, R, S](a: A, i: I)(implicit r: LogRenderer[I, V, R, S]): R =  r.addField("foo-field", StrValue(a.toString), i)
        }

        Logs.sync[F, F].forService(tag).flatMap { log =>
          fa.flatTap(result => log.info("Look ma, a call result: {}", result))
        }
      }
    }
    v.mapK(log)
  }

  val loggingInstance = logEveryCall(notLegacyInstance)
  program(loggingInstance, printer).unsafeRunSync()

  // Part 5 Going Hardcore
  // Context & Tracing
  // https://github.com/TinkoffCreditSystems/tofu/blob/master/doobie/src/test/scala/tofu/doobie/example/TofuDoobieExample.scala


}
