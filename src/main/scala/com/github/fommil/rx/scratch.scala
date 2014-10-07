package com.github.fommil.rx

import java.io.{ FileWriter, File }
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import scala.concurrent.{ Future, ExecutionContext }
import java.util.concurrent.{ CountDownLatch, Semaphore }
import com.google.common.base.{ Charsets, Stopwatch }
import akka.contrib.jul.JavaLogging
import scala.io.Source
import java.util.concurrent.atomic.AtomicLong
import ExecutionContext.Implicits.global
import GenerateData.rows
import com.google.common.io.Files
import rx.lang.scala.JavaConversions
import Files.newReader
import Charsets.UTF_8
import JavaConversions._
import rx.observables.StringObservable._
import scalaz.concurrent.Strategy
import scalaz.stream._
import scalaz.concurrent.Task

object Scratch extends App with GenerateData with JavaLogging {

  def timed[T <: AnyRef](text: String)(f: => Unit) {
    val watch = Stopwatch.createStarted()
    f
    log.info(text + " took " + watch)
  }

  val file = new File("data.csv")
  generate(file)

  //  timed("single threaded") {
  //    new SingleThreadParser(file).parse()
  //  }

  // timed("producer observable") {
  //   new ProducerObservableParser(file).parse()
  // }

  timed("scalaz streams") {
    new ScalazStreamsParser(file).parse()
  }

  //  timed("Throttled Future threaded") {
  //    new ThrottledFutureThreadParser(file).parse()
  //  }

  // TODO: Akka OOM. No point even writing it as a showcase.

  //  timed("Future threaded") {
  //    // OOM!
  //    new FutureThreadParser(file).parse()
  //  }

  //  timed("observable") {
  //    new ObservableParser(file).parse()
  //  }

}

trait GenerateData {

  def generate(file: File) {
    if (file.isFile) return
    val out = new FileWriter(file)
    try {
      (1 to rows) foreach { i =>
        out.write("blah,0,0.1\n")
      }
    } finally out.close()
  }

}

object GenerateData {
  val rows = 10000
}

trait ThrottledFutureSupport {
  private val futures = new Semaphore(4, true)

  // blocks when too many Futures are still processing
  protected def ThrottledFuture[T](block: => T)(implicit e: ExecutionContext) = {
    futures.acquire()
    Future {
      futures.release()
      block
    }
  }
}

trait ParseTest {
  def parse()

  private val count = new AtomicLong

  protected def parseLine(line: String): Long = {
    val bits = line.split(",")
    val b = bits(1).toInt
    val c = bits(2).toDouble

//    println("starting to sleep")
    Thread.sleep(10)
//    println("waking up")

    val done = count.incrementAndGet()
    if (done % 1000 == 0)
      println("done " + done)
    done
  }

}

trait Parser extends ParseTest {

  val file: File

  def handleLine(line: String)

  protected val latch = new CountDownLatch(rows)

  def parse() {
    Source.fromFile(file).getLines().foreach { line =>
      handleLine(line)
    }
    latch.await()
  }

  // simulates low CPU per-line processing
  protected override def parseLine(line: String) = {
    val res = super.parseLine(line)
    latch.countDown()
    res
  }

}

class SingleThreadParser(val file: File) extends Parser {
  def handleLine(line: String) {
    parseLine(line)
  }
}

class FutureThreadParser(val file: File) extends Parser {
  def handleLine(line: String) {
    Future {
      parseLine(line)
    }
  }
}

class ThrottledFutureThreadParser(val file: File) extends Parser with ThrottledFutureSupport {
  def handleLine(line: String) {
    ThrottledFuture {
      parseLine(line)
    }
  }
}

class ObservableParser(val file: File) extends ParseTest {
  private val latch = new CountDownLatch(1)

  override def parse() = {
    toScalaObservable(split(from(newReader(file, UTF_8)), "\n")).
      //      parallel(t => t). // ===> OOM
      subscribe(
        onNext = parseLine,
        onError = e => ???,
        onCompleted = () => latch.countDown()
      )
    latch.await()
  }
}

class ProducerObservableParser(val file: File) extends ParseTest {
  private val latch = new CountDownLatch(1)

  override def parse() = {
    val lines = Source.fromFile(file).getLines()

    class Producer extends BlockingProducer[String] {
      override def next() = {
        if (!lines.hasNext) throw new NoSuchElementException
        else lines.next()
      }
    }

    ProducerObservable.from(new Producer).
      subscribe(
        onNext = parseLine,
        onError = e => ???,
        onCompleted = () => latch.countDown()
      )
    latch.await()
  }

}

class ScalazStreamsParser(file: File) extends ParseTest {
 implicit val concurrency: Strategy = Strategy.Executor(
   new ForkJoinPool() // TODO: use the global fork/join
//   Executors.newCachedThreadPool()
//   Executors.newFixedThreadPool(16)
 )

  val lines: Process[Task, String] = io.linesR(file.getAbsolutePath)

  def process(line: String): Process[Task, Long] =
    Process.suspend(Process(parseLine(line)))
//  Process.eval(Task.delay(parseLine(line)))

  val consumers: Process[Task, Process[Task, Long]] = lines.map(process)

  val results: Process[Task, Long] =
    nondeterminism.njoin(maxOpen = 50, maxQueued = 100)(consumers)
 
  def parse(): Unit = results.run.run
}
