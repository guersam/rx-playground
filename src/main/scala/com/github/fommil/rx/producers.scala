package com.github.fommil.rx

import rx.lang.scala.Observable
import scala.concurrent.{Future, ExecutionContext}
import rx.lang.scala.JavaConversions._
import rx.Observable.OnSubscribe
import rx.Subscriber
import java.util.NoSuchElementException
import java.util.concurrent.{Callable, Executors, ArrayBlockingQueue}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.Duration
import scala.concurrent.blocking

// TODO: variant with timeout / backoff on `next`
trait BlockingProducer[+T] {
  /** Produce a new entity, blocking indefinitely until available.
    * Not thread safe, and must be called from one thread at a time.
    *
    * @throws NoSuchElementException if there are no more entities left.
    * @throws IOException if there was any side effect or data retrieval failure.
    */
  def next(): T
}

object ProducerObservable {

  // there is no ScalaSubscriber
  def create[T](work: Subscriber[_ >: T] => Unit) = toScalaObservable[T](
    rx.Observable.create(new OnSubscribe[T] {
      override def call(s: Subscriber[_ >: T]) = work(s)
    }))

  /**
   * Creates an `Observable` from a `BlockingProducer` that can be subscribed
   * by a single `Subscriber`. This is most appropriate for situations where
   * all of the following hold:
   *
   * 1. the producer is faster than (but not necessarily negligible to) a consumer.
   * 2. the producer may serve more entities than can retained in memory.
   * 3. there is a desire to consume many entities in parallel.
   *
   * In such situations, `Observable.parallel` may non-deterministically result
   * in an `OutOfMemoryException`.
   *
   * Events may be served in parallel and therefore slightly out of order.
   * Unsubscription may result in entities being lost forever and events may be received
   * briefly after an unsubscription (including errors).
   *
   * This takes best endeavours not to block any threads (but
   * may block when polling the producer or consumer).
   *
   * @param maxConcurrency places a limit on the number of parallel consumers
   * @param maxBuffer places a limit on the number of entities to read ahead
   * @param grace minimum time between polling the producer (e.g. hardware constraints),
   *              incurring the resource cost of a `ScheduledExecutor`. The system clock
   *              must be sensitive enough to schedule polling at this rate
   *              (e.g. `>1ms` on Linux, `>50ms` on some Windows).
   */
  def from[T <: AnyRef](producer: BlockingProducer[T],
                        maxConcurrency: Int = 16,
                        maxBuffer: Int = 64,
                        grace: Duration = Duration.Zero)
                       (implicit ctx: ExecutionContext): Observable[T] = {
    require(maxConcurrency >= 1)
    require(maxBuffer >= 2 * maxConcurrency, "buffer size must double the concurrency")
    val subscribed = new AtomicBoolean
    create { subscriber =>
      require(!subscribed.getAndSet(true), "only one subscriber allowed!")
      val state = new State[T](maxConcurrency, maxBuffer, grace)

      def worker() {
        if (subscriber.isUnsubscribed) return
        val choice = state.decide()
        if (choice eq Produce) {
          try {
            val entity = blocking { producer.next() }
            state.produced(entity)
          } catch {
            case e: NoSuchElementException =>
              state.exhaust()
            case e: Throwable if !subscriber.isUnsubscribed =>
              state.producedError()
              blocking { subscriber.onError(e) }
          }
        } else if (choice eq Rest) {
          return
        } else if (choice eq Complete) {
          blocking { subscriber.onCompleted() }
          println(s"RESTED ${state.rests } times")
          return
        } else {
          val entity = choice.asInstanceOf[T]
          blocking { subscriber.onNext(entity) }
          state.consumed()
        }

        state spawn worker
      }

      state spawn worker
    }
  }

  // the silent 4th member of Decision is a Consume[T],
  // but in order to save on object instantiation, we
  // just return T, so this Observable only supports AnyVal.
  private sealed trait Decision
  private case object Produce extends Decision
  private case object Rest extends Decision
  private case object Complete extends Decision

  private class State[T <: AnyRef](val maxConcurrency: Int,
                                   val maxBuffer: Int,
                                   grace: Duration) {
    state =>

    private var producing: Boolean = false
    private var exhausted: Boolean = false
    private var completed: Boolean = false
    private var outstanding: Int = 0
    private var consuming: Int = 0
    private val buffer = new ArrayBlockingQueue[T](maxBuffer)

    // debugging
    var rests = 0

    def consumed() = this synchronized {
      consuming -= 1
    }

    private val endProducer = () => {
      if (grace == Duration.Zero) {
        {producing = false }
      } else {
        val scheduler = Executors.newScheduledThreadPool(1)
        val caller = new Callable[Unit] {
          override def call() = state synchronized { producing = false }
        }
        {scheduler.schedule(caller, grace._1, grace._2) }
      }
    }: Unit

    def producedError() = this synchronized {
      endProducer()
    }

    def produced(entity: T) = this synchronized {
      // in reality this should never block
      blocking { buffer.put(entity) }
      endProducer()
    }

    def exhaust() = this synchronized {
      exhausted = true
      producing = false
    }

    def decide() = this synchronized {
      outstanding -= 1

      def produce() = {
        producing = true
        Produce
      }
      def consume() = {
        val hit = buffer.poll()
        if (hit == null) Rest
        else {
          consuming += 1
          hit
        }
      }
      if (exhausted) {
        if (buffer.isEmpty && !completed) {
          completed = true
          Complete
        } else consume()
      } else if (!producing && buffer.remainingCapacity > 0) {
        produce()
      } else if (!buffer.isEmpty && consuming < maxConcurrency) {
        consume()
      } else {
        rests += 1
        Rest
      }
    }

    override def toString = this synchronized {
      s"producing=$producing,consuming=$consuming,buffered=${buffer.size },outstanding=$outstanding"
    }

    def spawn(worker: => Unit)(implicit ctx: ExecutionContext) = {
      var spawning = this synchronized {
        val todo = maxConcurrency - consuming - outstanding
        outstanding += todo
        todo
      }
      while (spawning > 0) {
        spawning -= 1
        Future { worker }
      }
    }
  }

}
