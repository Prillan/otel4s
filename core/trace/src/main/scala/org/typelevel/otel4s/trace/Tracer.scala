/*
 * Copyright 2022 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.otel4s
package trace

import cats.Applicative
import cats.effect.kernel.CancelScope
import cats.effect.kernel.MonadCancelThrow
import cats.effect.kernel.Poll
import cats.effect.kernel.Resource
import cats.~>
import org.typelevel.otel4s.meta.InstrumentMeta
import cats.data.Kleisli
import cats.arrow.FunctionK

@annotation.implicitNotFound("""
Could not find the `Tracer` for ${F}. `Tracer` can be one of the following:

1) No-operation (a.k.a. without tracing)

import Tracer.Implicits.noop

2) Manually from TracerProvider

val tracerProvider: TracerProvider[IO] = ???
tracerProvider
  .get("com.service.runtime")
  .flatMap { implicit tracer: Tracer[IO] => ??? }
""")
trait Tracer[F[_]] extends TracerMacro[F] {

  /** The instrument's metadata. Indicates whether instrumentation is enabled or
    * not.
    */
  def meta: Tracer.Meta[F]

  /** Returns the context of a span when it is available in the scope.
    */
  def currentSpanContext: F[Option[SpanContext]]

  /** Creates a new [[SpanBuilder]]. The builder can be used to make a fully
    * customized [[Span]].
    *
    * @param name
    *   the name of the span
    */
  def spanBuilder(name: String): SpanBuilder.Aux[F, Span[F]]

  /** Creates a new tracing scope with a custom parent. A newly created non-root
    * span will be a child of the given `parent`.
    *
    * @example
    *   {{{
    * val tracer: Tracer[F] = ???
    * val span: Span[F] = ???
    * val customChild: F[A] =
    *   tracer.childScope(span.context) {
    *     tracer.span("custom-parent").use { span => ??? }
    *   }
    *   }}}
    *
    * @param parent
    *   the span context to use as a parent
    */
  def childScope[A](parent: SpanContext)(fa: F[A]): F[A]

  /** Creates a new tracing scope if the given `parent` is defined. A newly
    * created non-root span will be a child of the given `parent`.
    *
    * @see
    *   [[childScope]]
    *
    * @param parent
    *   the span context to use as a parent
    */
  final def childOrContinue[A](parent: Option[SpanContext])(fa: F[A]): F[A] =
    parent match {
      case Some(ctx) =>
        childScope(ctx)(fa)
      case None =>
        fa
    }

  /** Creates a new tracing scope if a parent can be extracted from the given
    * `carrier`. A newly created non-root span will be a child of the extracted
    * parent.
    *
    * If the context cannot be extracted from the `carrier`, the given effect
    * `fa` will be executed within the '''root''' span.
    *
    * To make the propagation and extraction work, you need to configure the
    * OpenTelemetry SDK. For example, you can use `OTEL_PROPAGATORS` environment
    * variable. See the official
    * [[https://opentelemetry.io/docs/reference/specification/sdk-environment-variables/#general-sdk-configuration SDK configuration guide]].
    *
    * ==Examples==
    *
    * ===Propagation via [[https://www.w3.org/TR/trace-context W3C headers]]:===
    * {{{
    * val w3cHeaders: Map[String, String] =
    *   Map("traceparent" -> "00-80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-01")
    *
    * Tracer[F].joinOrRoot(w3cHeaders) {
    *   Tracer[F].span("child").use { span => ??? } // a child of the external span
    * }
    * }}}
    *
    * ===Start a root span as a fallback:===
    * {{{
    * Tracer[F].span("process").surround {
    *   Tracer[F].joinOrRoot(Map.empty) { // cannot extract the context from the empty map
    *     Tracer[F].span("child").use { span => ??? } // a child of the new root span
    *   }
    * }
    * }}}
    *
    * @param carrier
    *   the carrier to extract the context from
    *
    * @tparam C
    *   the type of the carrier
    */
  def joinOrRoot[A, C: TextMapGetter](carrier: C)(fa: F[A]): F[A]

  /** Creates a new root tracing scope. The parent span will not be available
    * inside. Thus, a span created inside of the scope will be a root one.
    *
    * Can be useful, when an effect needs to be executed in the background and
    * the parent tracing info is not needed.
    *
    * @example
    *   the parent is not propagated:
    *   {{{
    * val tracer: Tracer[F] = ???
    * tracer.span("root-span").use { _ =>
    *   for {
    *     _ <- tracer.span("child-1").use(_ => ???) // a child of 'root-span'
    *     _ <- tracer.rootScope {
    *       tracer.span("child-2").use(_ => ???) // a root span that is not associated with 'root-span'
    *     }
    *   } yield ()
    * }
    *   }}}
    */
  def rootScope[A](fa: F[A]): F[A]

  /** Creates a no-op tracing scope. The tracing operations inside of the scope
    * are no-op.
    *
    * @example
    *   the parent is not propagated:
    *   {{{
    * val tracer: Tracer[F] = ???
    * tracer.span("root-span").use { _ =>
    *   for {
    *     _ <- tracer.span("child-1").use(_ => ???) // a child of 'root-span'
    *     _ <- tracer.noopScope {
    *       tracer.span("child-2").use(_ => ???) // 'child-2' is not created at all
    *     }
    *   } yield ()
    * }
    *   }}}
    */
  def noopScope[A](fa: F[A]): F[A]

  def translate[G[_]](U: Unlift[F, G]): Tracer[G]

}

object Tracer {

  def apply[F[_]](implicit ev: Tracer[F]): Tracer[F] = ev

  trait Meta[F[_]] extends InstrumentMeta[F] {
    def noopSpanBuilder: SpanBuilder.Aux[F, Span[F]]
    final def noopResSpan[A](
        resource: Resource[F, A]
    ): SpanBuilder.Aux[F, Span.Res[F, A]] =
      noopSpanBuilder.wrapResource(resource)
  }

  object Meta {

    def enabled[F[_]: MonadCancelThrow]: Meta[F] = make(true)
    def disabled[F[_]: MonadCancelThrow]: Meta[F] = make(false)

    private def make[F[_]: MonadCancelThrow](enabled: Boolean): Meta[F] =
      new Meta[F] {
        private val noopBackend = Span.Backend.noop[F]

        val isEnabled: Boolean = enabled
        val unit: F[Unit] = Applicative[F].unit
        val noopSpanBuilder: SpanBuilder.Aux[F, Span[F]] =
          SpanBuilder.noop(noopBackend)
      }
  }

  def noop[F[_]: MonadCancelThrow]: Tracer[F] =
    new Tracer[F] {
      private val noopBackend = Span.Backend.noop
      private val builder = SpanBuilder.noop(noopBackend)
      val meta: Meta[F] = Meta.disabled
      val currentSpanContext: F[Option[SpanContext]] = Applicative[F].pure(None)
      def rootScope[A](fa: F[A]): F[A] = fa
      def noopScope[A](fa: F[A]): F[A] = fa
      def childScope[A](parent: SpanContext)(fa: F[A]): F[A] = fa
      def spanBuilder(name: String): SpanBuilder.Aux[F, Span[F]] = builder
      def joinOrRoot[A, C: TextMapGetter](carrier: C)(fa: F[A]): F[A] = fa
      def translate[G[_]](U: Unlift[F, G]): Tracer[G] =
        noop[G](liftMonadCancelThrow[F, G](MonadCancelThrow[F], U))
    }

  object Implicits {
    implicit def noop[F[_]: MonadCancelThrow]: Tracer[F] = Tracer.noop
  }

  private def liftMonadCancelThrow[F[_], G[_]](
      F: MonadCancelThrow[F],
      U: Unlift[F, G]
  ): MonadCancelThrow[G] =
    new MonadCancelThrow[G] {
      def pure[A](x: A): G[A] = U.liftF(F.pure(x))

      // Members declared in cats.ApplicativeError
      def handleErrorWith[A](ga: G[A])(f: Throwable => G[A]): G[A] =
        U.withUnlift { gk =>
          F.handleErrorWith(gk(ga))(ex => gk(f(ex)))
        }

      def raiseError[A](e: Throwable): G[A] = U.liftF(F.raiseError[A](e))

      // Members declared in cats.FlatMap
      def flatMap[A, B](ga: G[A])(f: A => G[B]): G[B] =
        U.withUnlift { gk => F.flatMap(gk(ga))(a => gk(f(a))) }

      def tailRecM[A, B](a: A)(f: A => G[Either[A, B]]): G[B] =
        U.withUnlift { gk => F.tailRecM(a)(a => gk(f(a))) }

      // Members declared in cats.effect.kernel.MonadCancel
      def canceled: G[Unit] = U.liftF(F.canceled)

      def forceR[A, B](ga: G[A])(gb: G[B]): G[B] =
        U.withUnlift { gk => F.forceR(gk(ga))(gk(gb)) }

      def onCancel[A](ga: G[A], fin: G[Unit]): G[A] =
        U.withUnlift { gk => F.onCancel(gk(ga), gk(fin)) }

      def rootCancelScope: CancelScope = F.rootCancelScope

      def uncancelable[A](body: Poll[G] => G[A]): G[A] =
        U.withUnlift { gk =>
          F.uncancelable { pollF =>
            gk(body(new Poll[G] {
              def apply[B](gb: G[B]): G[B] = U.liftF(pollF(gk(gb)))
            }))
          }
        }
    }
}

trait Unlift[F[_], G[_]] { self =>
  def withUnlift[A](f: (G ~> F) => F[A]): G[A]

  def compose[H[_]](U: Unlift[G, H]): Unlift[F, H] = new Unlift[F, H] {
    override def withUnlift[A](f: (H ~> F) => F[A]): H[A] =
      U.withUnlift[A] { outer =>
        self.withUnlift[A] { inner =>
          f(inner.compose(outer))
        }
      }
  }

  def liftK: F ~> G =
    new (F ~> G) {
      override def apply[A](fa: F[A]): G[A] =
        withUnlift { _ => fa }
    }

  def liftF[A](fa: F[A]): G[A] =
    withUnlift(_ => fa)
}

object Unlift {
  implicit def unliftKleisli[F[_], E]: Unlift[F, Kleisli[F, E, *]] =
    new Unlift[F, Kleisli[F, E, *]] {
      override def withUnlift[A](
          f: (Kleisli[F, E, *] ~> F) => F[A]
      ): Kleisli[F, E, A] =
        Kleisli { env =>
          f(Kleisli.applyK(env))
        }

      override def liftF[A](fa: F[A]): Kleisli[F, E, A] = Kleisli.liftF(fa)
      override def liftK: F ~> Kleisli[F, E, *] = Kleisli.liftK
    }

  implicit def unliftId[F[_]]: Unlift[F, F] = new Unlift[F, F] {
    override def withUnlift[A](f: (F ~> F) => F[A]): F[A] =
      f(FunctionK.id)

    override def liftF[A](fa: F[A]): F[A] = fa
    override def liftK: F ~> F = FunctionK.id
  }
}
