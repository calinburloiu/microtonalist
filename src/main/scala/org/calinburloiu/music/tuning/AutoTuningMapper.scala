/*
 * Copyright 2020 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.calinburloiu.music.tuning

import java.util.concurrent.TimeUnit

import com.google.common.cache.{CacheBuilder, CacheLoader, RemovalNotification}
import com.google.common.util.concurrent.UncheckedExecutionException
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.{Interval, PitchClass, Scale}
import org.calinburloiu.music.plugin.{PluginConfig, PluginFactory}

import scala.util.Try

class AutoTuningMapper(val pitchClassConfig: PitchClassConfig = PitchClassConfig())
    extends TuningMapper(None) { // TODO #3 Remove base class config

  private[this] implicit val implicitPitchClassConfig: PitchClassConfig = pitchClassConfig

  def this(mapQuarterTonesLow: Boolean) =
    this(PitchClassConfig(mapQuarterTonesLow, PitchClassConfig.DefaultHalfTolerance))

  override def apply(basePitchClass: PitchClass, scale: Scale[Interval]): PartialTuning = {
    // TODO Refactor (check commented lines or think about a generic solution like KeyboardMapper).
//    val pitchClasses: Seq[PitchClass] = scale.intervals
//      .map(_.normalize).distinct
//      .map { interval =>
//        val cents = interval.cents + basePitchClass.cents
//        Converters.fromCentsToPitchClass(cents, autoTuningMapperConfig.mapQuarterTonesLow)
//      }
    val pitchClasses: Seq[PitchClass] = scale.intervals.map { interval =>
      basePitchClass + interval
    }.distinct

    val groupsOfPitchClasses = pitchClasses.groupBy(_.semitone)
    val pitchClassesWithConflicts = groupsOfPitchClasses
      .filter(_._2.distinct.lengthCompare(1) > 0)
    if (pitchClassesWithConflicts.nonEmpty) {
      throw new AutoTuningMapperException(
          "Cannot tune automatically, some pitch classes have conflicts:" +
              pitchClassesWithConflicts)
    } else {
      val pitchClassesMap = pitchClasses.map(PitchClass.unapply(_).get).toMap
      val partialTuningValues = (0 until 12).map { index =>
        pitchClassesMap.get(index)
      }

      PartialTuning(partialTuningValues)
    }
  }

  override def toString: String = s"AutoTuningMapper($pitchClassConfig)"

  def canEqual(other: Any): Boolean = other.isInstanceOf[AutoTuningMapper]

  // TODO #3 Regenerate equals, hashCode, toString after refactoring
  override def equals(other: Any): Boolean = other match {
    case that: AutoTuningMapper =>
      (that canEqual this) &&
        pitchClassConfig == that.pitchClassConfig
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(pitchClassConfig)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

object AutoTuningMapper {

  val pluginId: String = "auto"
}

class AutoTuningMapperFactory extends PluginFactory[AutoTuningMapper] with StrictLogging {

  override val pluginId: String = AutoTuningMapper.pluginId

  override val configClass: Option[Class[AutoTuningMapperConfig]] =
      Some(classOf[AutoTuningMapperConfig])

  override lazy val defaultConfig: Option[AutoTuningMapperConfig] =
    Some(AutoTuningMapperConfig(mapQuarterTonesLow = false,
        halfTolerance = PitchClassConfig.DefaultHalfTolerance))

  private[this] val cache = CacheBuilder.newBuilder()
    .maximumSize(8)
    .expireAfterAccess(7, TimeUnit.DAYS)
    .removalListener { notification: RemovalNotification[PluginConfig, AutoTuningMapper] =>
      logger.info(s"Plugin with ID ${notification.getKey} ${notification.getValue} was removed " +
          s"from cache: cause=${notification.getCause} evicted=${notification.wasEvicted()}")
    }
    .build(new CacheLoader[PluginConfig, AutoTuningMapper] {
      override def load(config: PluginConfig): AutoTuningMapper = config match {
        case autoConfig: AutoTuningMapperConfig => new AutoTuningMapper(PitchClassConfig(autoConfig.mapQuarterTonesLow, autoConfig.halfTolerance))
        case otherConfig => throw new IllegalArgumentException(
          s"Expecting a specific AutoTuningMapperConfig, but got ${otherConfig.getClass.getName}")
      }
    })


  override def create(config: Option[PluginConfig]): AutoTuningMapper = Try {
    val actualConfig = (config ++ defaultConfig).head
    cache.getUnchecked(actualConfig)
  }.recover {
    case e: UncheckedExecutionException => throw e.getCause
  }.get
}

// TODO This config is the same as PitchClassConfig!
case class AutoTuningMapperConfig(
  mapQuarterTonesLow: Boolean,
  halfTolerance: Double = PitchClassConfig.DefaultHalfTolerance
) extends TuningMapperConfig

class AutoTuningMapperException(message: String) extends TuningMapperException(message, null)
