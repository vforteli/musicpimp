package com.mle.musicpimp.scheduler

import it.sauronsoftware.cron4j.SchedulingPattern
import play.api.libs.json.Json

/**
 * Schedule for executing something every `interval` `timeUnit`s.
 *
 * @param interval time amount - TODO check which ranges are valid
 * @param timeUnit time unit
 * @param days the weekdays during which this schedule is valid
 */
case class IntervalSchedule(interval: Int, timeUnit: TimeUnit, days: Seq[WeekDay]) extends DaySchedule {
  def cronPattern = {
    def minutesHoursDays =
      timeUnit match {
        case Minutes => s"*/$interval * *"
        case Hours => s"* */$interval *"
        case Days => s"* * */$interval"
      }
    new SchedulingPattern(s"$minutesHoursDays * $daysStringified")
  }

  def describe: String = s"every $interval $timeUnit on $daysReadable"
}

object IntervalSchedule {
  implicit val format = Json.format[IntervalSchedule]
}