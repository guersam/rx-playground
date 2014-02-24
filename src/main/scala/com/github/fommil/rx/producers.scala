package com.github.fommil.rx

import rx.lang.scala.Observable
import scala.concurrent.{Lock, Future, ExecutionContext}
import rx.lang.scala.JavaConversions._
import rx.Observable.OnSubscribe
import rx.Subscriber
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection._
import java.util.NoSuchElementException
import java.io.IOException

// More specialised than Iterator.
// TODO: variant with timeouts on `next`
// TODO: variant with parallel `next`
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

  class State[T] {
    // we need to access values as mutable references
    var producing: Boolean = false
    var exhausted: Boolean = false
    var consuming: Int = 0
    val buffer = mutable.Queue.empty[T]
  }

  /*
    This Observable may serve events in parallel to a sole Subscriber.
    Unsubscription may result in obtaining results from the producer that are never
    sent to the subscriber.
  */
  def from[T](producer: BlockingProducer[T],
              maxConcurrency: Int = 16,
              maxBuffer: Int = 32)
             (implicit ctx: ExecutionContext): Observable[T] = {
    require(maxConcurrency >= 1)
    create { subscriber =>

      val state = new State[T]

      (1 to maxConcurrency) foreach { _ =>
        Future {
          decide[T](producer, subscriber, maxConcurrency, maxBuffer, state)
        }
      }
    }
  }


  private trait Decision

  private case object Produce extends Decision

  @deprecated
  private case class Consume[T](entity: T) extends Decision

  private case object Rest extends Decision

  /*
    The multi-threaded producer / consumer algorithm is:

  1. if there are entities in the buffer, and we're using less than maxConcurrency threads,
     spawn enough Futures to bring us to the limit and have each one take an entity
     from the buffer and give it to the subscriber. In addition, go to 2.
  2. if the buffer is not full, and we're not already producing, produce more.
  3. give the thread resource back to the pool (there should always be at least
     one outstanding worker if this happens)

  when a thread finishes producing or consuming, it should decide what to do
  again.

   */
  private def decide[T](producer: BlockingProducer[T],
                        subscriber: Subscriber[_ >: T],
                        maxConcurrency: Int,
                        maxBuffer: Int,
                        state: State[T])
                       (implicit ctx: ExecutionContext) {

    // TODO: this bit without a lock would be awesome
    val choice: Decision = state synchronized {
      if (subscriber.isUnsubscribed) Rest
      else if (state.exhausted && state.buffer.isEmpty) {
        subscriber.onCompleted()
        Rest
      } else if (!state.exhausted && !state.producing && state.buffer.size < maxBuffer) {
        state.producing = true
        Produce
      } else if (!state.buffer.isEmpty && state.consuming <= maxConcurrency) {
        state.consuming += 1
        Consume(state.buffer.dequeue())
      } else {
        Rest
      }
    }

    // TODO: fix superfluous Rests... they should be a small proportion of decisions

    val spawn: Int = if (choice eq Rest) {
//    if ((choice eq Produce) || choice.isInstanceOf[Consume[_]])
      println(s"$choice ${state.producing} ${state.consuming} from ${state.buffer.size} ${state.exhausted}")
      0
    } else if (choice eq Produce) {
      try {
        // blocking :-(
        val entity = producer.next()
        state synchronized {
          state.buffer.enqueue(entity)
          state.producing = false
          1 + (if (state.consuming > 1) 0 else 1)
        }
      } catch {
        case e: NoSuchElementException =>
          state synchronized {
            state.exhausted = true
            state.producing = false
            if (state.consuming > 0) 0 else 1
          }
        case e: IOException if !subscriber.isUnsubscribed =>
          subscriber.onError(e)
          1
      }
    } else {
      val entity = choice.asInstanceOf[Consume[T]]
      if (!subscriber.isUnsubscribed)
        subscriber.onNext(entity.entity)
      state synchronized {
        state.consuming -= 1
      }
      if (state.consuming < maxConcurrency) 2 else 1
    }

    if (spawn > 0) {
      (1 to spawn) foreach { _ =>
        Future {
          // work is rescheduled as Future, rather than recursive
          decide[T](producer, subscriber, maxConcurrency, maxBuffer, state)
        }
      }
    }
  }


}
