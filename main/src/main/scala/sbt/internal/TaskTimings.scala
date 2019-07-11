/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package internal

import sbt.internal.util.{ RMap, ConsoleOut }

/**
 * Measure the time elapsed for running tasks.
 * This class is activated by adding -Dsbt.task.timing=true to the JVM options.
 * Formatting options:
 * - -Dsbt.task.timings.on.shutdown=true|false
 * - -Dsbt.task.timings.unit=number
 * - -Dsbt.task.timings.threshold=number
 * @param reportOnShutdown    Should the report be given when exiting the JVM (true) or immediately (false)?
 */
private[sbt] final class TaskTimings(reportOnShutdown: Boolean)
    extends AbstractTaskExecuteProgress
    with ExecuteProgress[Task] {
  import AbstractTaskExecuteProgress.Timer
  private[this] var start = 0L
  private[this] val threshold = SysProp.taskTimingsThreshold
  private[this] val omitPaths = SysProp.taskTimingsOmitPaths
  private[this] val (unit, divider) = SysProp.taskTimingsUnit

  if (reportOnShutdown) {
    start = System.nanoTime
    ShutdownHooks.add(() => report())
  }

  override def initial(): Unit = {
    if (!reportOnShutdown)
      start = System.nanoTime
  }

  override def afterReady(task: Task[_]): Unit = ()
  override def afterCompleted[T](task: Task[T], result: Result[T]): Unit = ()
  override def afterAllCompleted(results: RMap[Task, Result]): Unit =
    if (!reportOnShutdown) {
      report()
    }

  override def stop(): Unit = ()

  private[this] val reFilePath = raw"\{[^}]+\}".r
  private[this] val console = ConsoleOut.systemOut

  private[this] def report() = {
    val total = divide(System.nanoTime - start)
    console.println(s"Total time: $total $unit")
    import collection.JavaConverters._
    def sumTimes(in: Seq[(Task[_], Timer)]) = in.map(_._2.durationNanos).sum
    val timingsByName = timings.asScala.toSeq.groupBy { case (t, _) => taskName(t) } mapValues (sumTimes)
    val times = timingsByName.toSeq
      .sortBy(_._2)
      .reverse
      .map {
        case (name, time) =>
          (if (omitPaths) reFilePath.replaceFirstIn(name, "") else name, divide(time))
      }
      .filter { _._2 > threshold }
    if (times.size > 0) {
      val maxTaskNameLength = times.map { _._1.length }.max
      val maxTime = times.map { _._2 }.max.toString.length
      times.foreach {
        case (taskName, time) =>
          console.println(s"  ${taskName.padTo(maxTaskNameLength, ' ')}: ${""
            .padTo(maxTime - time.toString.length, ' ')}$time $unit")
      }
    }
  }

  private[this] def divide(time: Long) = (1L to divider.toLong).fold(time) { (a, b) =>
    a / 10L
  }
}
