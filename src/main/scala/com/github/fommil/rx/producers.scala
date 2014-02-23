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

    // TODO: repackage these mutables
    // in lieu of atomic compareTo
      val lock = new Lock
      // we need to access values as mutable references
      val producing = new AtomicBoolean
      val consuming = new AtomicInteger
      val exhausted = new AtomicBoolean
      val buffer = mutable.Queue.empty[T]

      (1 to maxConcurrency) foreach { _ =>
        Future {
          decide[T](producer, lock, producing, consuming, exhausted, buffer, subscriber, maxConcurrency, maxBuffer)
        }
      }
    }
  }


  private trait Decision

  private case object Produce extends Decision

  private case class Consume[T](entity: T) extends Decision

  private case class SpawnConsume(count: Int) extends Decision

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
                        lock: Lock,
                        producing: AtomicBoolean,
                        consuming: AtomicInteger,
                        exhausted: AtomicBoolean,
                        buffer: mutable.Queue[T],
                        subscriber: Subscriber[_ >: T],
                        maxConcurrency: Int,
                        maxBuffer: Int)
                       (implicit ctx: ExecutionContext) {

    // TODO: this bit without a lock would be awesome
    val choice: Decision = lock synchronized {
      if (subscriber.isUnsubscribed) Rest
      else if (exhausted.get && buffer.isEmpty) {
        subscriber.onCompleted()
        Rest
      } else if (!exhausted.get && !producing.get && buffer.size < maxBuffer) {
        producing set true
        Produce
      } else if (!buffer.isEmpty && consuming.get <= maxConcurrency) {
        consuming.getAndIncrement()
        Consume(buffer.dequeue())
      } else {
        Rest
      }
    }

    // TODO: fix superfluous Rests... they should be a small proportion of decisions

    if (choice eq Rest)
//    if ((choice eq Produce) || choice.isInstanceOf[Consume[_]])
      println(s"$choice $producing $consuming from ${buffer.size} $exhausted")

    if (choice eq Produce) {
      try {
        // blocking :-(
        val entity = producer.next()
        lock synchronized {
          buffer.enqueue(entity)
          producing set false
        }
      } catch {
        case e: NoSuchElementException =>
          lock synchronized {
            exhausted set true
            producing set false
          }
        case e: IOException if !subscriber.isUnsubscribed =>
          subscriber.onError(e)
      }
    }

    else if (choice.isInstanceOf[Consume[_]]) {
      val entity = choice.asInstanceOf[Consume[T]]
      if (!subscriber.isUnsubscribed)
        subscriber.onNext(entity.entity)
      lock synchronized {
        consuming.getAndDecrement()
      }
    }


    val spawn = lock synchronized {
      val potential = 0 max (buffer.size min (maxConcurrency - consuming.get - 1))
      if (potential == 0 && !producing.get && consuming.get == 0) 1
      else potential
    }
    if (spawn > 0) {
      (1 to spawn) foreach { _ =>
        Future {
          // work is rescheduled as Future, rather than recursive
          decide[T](producer, lock, producing, consuming, exhausted, buffer, subscriber, maxConcurrency, maxBuffer)
        }
      }
    }
  }


}
