package com.github.fommil.rx

import java.io.{FileWriter, File}
import scala.concurrent.{Future, ExecutionContext}
import java.util.concurrent.{CountDownLatch, Semaphore}
import com.google.common.base.{Charsets, Stopwatch}
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


object Scratch extends App with GenerateData with JavaLogging {

  def timed[T <: AnyRef](text: String)(f: => Unit) {
    val watch = Stopwatch.createStarted()
    f
    log.info(text + " took " + watch)
  }

  val file = new File("data.csv")
  generate(file)

  timed("single threaded") {
    new SingleThreadParser(file).parse()
  }

  timed("Throttled Future threaded") {
    new ThrottledFutureThreadParser(file).parse()
  }

  // TODO: Akka OOM

//  timed("Future threaded") {
//    // OOM!
//    new FutureThreadParser(file).parse()
//  }

  timed("observable") {
    new ObservableParser(file).parse()
  }

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
  val rows = 10000000
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

  protected def parseLine(line: String) = {
    //    val watch = Stopwatch.createStarted()
    val bits = line.split(",")
    val b = bits(1).toInt
    val c = bits(2).toDouble

//    Thread.sleep(1)

    val done = count.incrementAndGet()
//    if (done % 100000 == 0)
//      println("done " + done)
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
    super.parseLine(line)
    latch.countDown()
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
//      parallel(t => t).
      subscribe(
      onNext = parseLine,
      onError = e => ???,
      onCompleted = () => latch.countDown()
    )
    latch.await()
  }
}