package core.Http4s.authentication

import _root_.authenticationSimple.client.{ http4s => cdefs }
import _root_.authenticationSimple.server.http4s.auth.AuthHandler
import _root_.authenticationSimple.server.http4s.auth.AuthResource
import _root_.authenticationSimple.server.http4s.auth.AuthResource._
import authenticationSimple.client.http4s.auth.AuthClient
import cats.data._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.Printer
import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.http4s.syntax.StringSyntax
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import org.http4s.AuthScheme
import authenticationSimple.server.http4s.auth.AuthResource.AuthSchemes.Basic
import authenticationSimple.server.http4s.auth.AuthResource.AuthSchemes.Jwt
import authenticationSimple.server.http4s.auth.AuthResource.AuthSchemes.OAuth2

class Http4sSimpleAuthenticationTest extends AnyFunSuite with Matchers with EitherValues with StringSyntax {
  type AuthContext = String

  def createServer(authMiddleware: (AuthResource.AuthSchemes, Set[String], Request[IO]) => IO[Either[AuthResource.AuthError, AuthContext]]): HttpRoutes[IO] =
    new AuthResource[IO, AuthContext](authMiddleware).routes(new AuthHandler[IO, AuthContext] {
      override def doBar(respond: DoBarResponse.type)(body: String): IO[DoBarResponse] = ???
      override def doFoo(respond: DoFooResponse.type)(authContext: Either[AuthResource.AuthError, AuthContext], body: String): IO[DoFooResponse] =
        authContext.fold(
          {
            case AuthResource.AuthError.Forbidden    => IO(DoFooResponse.Ok("authentication failed: forbidden"))
            case AuthResource.AuthError.Unauthorized => IO(DoFooResponse.Ok("authentication failed: unauthorized"))
          },
          ctx => IO(DoFooResponse.Ok(ctx))
        )
    })

  def request(server: HttpRoutes[IO]): IO[String] =
    Client
      .fromHttpApp(server.orNotFound)
      .run(
        Request[IO](method = Method.POST, uri = Uri.unsafeFromString("/foo"))
          .withBodyStream(fs2.Stream.apply("\"-97-\"".getBytes(): _*))
          .withContentType(`Content-Type`(MediaType.application.json))
      )
      .use(_.bodyText.compile.string)
      .attempt
      .map(_.value)

  test("successful authentication") {
    val authMiddleware = (_: AuthResource.AuthSchemes, _: Set[String], _: Request[IO]) => IO.pure(Right("success"))
    val server         = createServer(authMiddleware)
    val result         = request(server).unsafeRunSync()

    result shouldEqual s""""success""""
  }

  test("failed authentication") {
    val authMiddleware = (_: AuthResource.AuthSchemes, _: Set[String], _: Request[IO]) => IO.pure(Left(AuthResource.AuthError.Unauthorized))
    val server         = createServer(authMiddleware)
    val result         = request(server).unsafeRunSync()

    result shouldEqual s""""authentication failed: unauthorized""""
  }

  test("provide security requirements to authentication") {
    import AuthResource.AuthSchemes

    val authMiddleware1: (AuthSchemes, Set[String], Request[IO]) => IO[Either[AuthResource.AuthError, AuthContext]] = {
      case (AuthSchemes.Jwt, s, _) if s.forall(Set("foo:read", "bar:write").contains(_)) => IO.pure(Right("success"))
      case (AuthSchemes.Basic, s, _) if s.forall(Set("bar:basic").contains(_))           => IO.pure(Right("success"))
      case (AuthSchemes.OAuth2, s, _) if s.forall(Set("oauth:scope").contains(_))        => IO.pure(Left(AuthResource.AuthError.Unauthorized))
      case (_, _, _)                                                                     => IO.pure(Left(AuthResource.AuthError.Unauthorized))
    }
    val server1 = createServer(authMiddleware1)
    val result1 = request(server1).unsafeRunSync()

    val authMiddleware2: (AuthSchemes, Set[String], Request[IO]) => IO[Either[AuthResource.AuthError, AuthContext]] = {
      case (AuthSchemes.Jwt, s, _) if s.forall(Set("foo:read", "bar:write").contains(_)) => IO.pure(Left(AuthResource.AuthError.Unauthorized))
      case (AuthSchemes.OAuth2, s, _) if s.forall(Set("oauth:scope").contains(_))        => IO.pure(Right("success"))
      case (_, _, _)                                                                     => IO.pure(Left(AuthResource.AuthError.Unauthorized))
    }
    val server2 = createServer(authMiddleware2)
    val result2 = request(server2).unsafeRunSync()

    result1 shouldEqual s""""success""""
    result2 shouldEqual s""""success""""
  }

  test("processing order: shortcut on the first 'or' success") {
    import AuthResource.AuthSchemes

    val invoked = mutable.ArrayBuffer.empty[AuthSchemes]
    val authMiddleware: (AuthSchemes, Set[String], Request[IO]) => IO[Either[AuthResource.AuthError, AuthContext]] = { (authScheme, scopes, req) =>
      invoked += authScheme
      authScheme match {
        case AuthSchemes.Basic  => IO.pure(Right("success"))
        case AuthSchemes.Jwt    => IO.pure(Right("success"))
        case AuthSchemes.OAuth2 => IO.pure(Left(AuthResource.AuthError.Unauthorized))
      }
    }
    val server = createServer(authMiddleware)
    val result = request(server).unsafeRunSync()

    invoked should contain theSameElementsInOrderAs Seq(AuthSchemes.Basic, AuthSchemes.Jwt)
    result shouldEqual s""""success""""
  }

  test("processing order: shortcut on the first 'and' failure") {
    import AuthResource.AuthSchemes

    val invoked = mutable.ArrayBuffer.empty[AuthSchemes]
    val authMiddleware: (AuthSchemes, Set[String], Request[IO]) => IO[Either[AuthResource.AuthError, AuthContext]] = { (authScheme, scopes, req) =>
      invoked += authScheme
      authScheme match {
        case AuthSchemes.Basic  => IO.pure(Left(AuthResource.AuthError.Unauthorized))
        case AuthSchemes.Jwt    => IO.pure(Left(AuthResource.AuthError.Unauthorized))
        case AuthSchemes.OAuth2 => IO.pure(Left(AuthResource.AuthError.Forbidden))
      }
    }
    val server = createServer(authMiddleware)
    val result = request(server).unsafeRunSync()

    invoked should contain theSameElementsInOrderAs Seq(AuthSchemes.Basic, AuthSchemes.OAuth2)
    result shouldEqual s""""authentication failed: forbidden""""
  }
}
