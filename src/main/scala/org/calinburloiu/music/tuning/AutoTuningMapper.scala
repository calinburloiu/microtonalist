package org.calinburloiu.music.tuning

import java.util.concurrent.TimeUnit

import com.google.common.cache.{CacheBuilder, CacheLoader, RemovalNotification}
import com.google.common.util.concurrent.UncheckedExecutionException
import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.intonation.{Interval, PitchClass, Scale}
import org.calinburloiu.music.plugin.{PluginConfig, PluginFactory}

import scala.util.Try

class AutoTuningMapper(protected val autoTuningMapperConfig: AutoTuningMapperConfig)
    extends TuningMapper(Some(autoTuningMapperConfig)) {

  implicit val pitchClassConfig: PitchClassConfig =
    PitchClassConfig(autoTuningMapperConfig.mapQuarterTonesLow, autoTuningMapperConfig.halfTolerance)

  def this(mapQuarterTonesLow: Boolean) =
    this(AutoTuningMapperConfig(mapQuarterTonesLow, PitchClassConfig.DEFAULT_HALF_TOLERANCE))

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

  override def toString: String = s"AutoTuningMapper($autoTuningMapperConfig)"

  def canEqual(other: Any): Boolean = other.isInstanceOf[AutoTuningMapper]

  override def equals(other: Any): Boolean = other match {
    case that: AutoTuningMapper =>
      (that canEqual this) &&
        autoTuningMapperConfig == that.autoTuningMapperConfig
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(autoTuningMapperConfig)
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
        halfTolerance = PitchClassConfig.DEFAULT_HALF_TOLERANCE))

  private[this] val cache = CacheBuilder.newBuilder()
    .maximumSize(8)
    .expireAfterAccess(7, TimeUnit.DAYS)
    .removalListener { notification: RemovalNotification[PluginConfig, AutoTuningMapper] =>
      logger.info(s"Plugin with ID ${notification.getKey} ${notification.getValue} was removed " +
          s"from cache: cause=${notification.getCause} evicted=${notification.wasEvicted()}")
    }
    .build(new CacheLoader[PluginConfig, AutoTuningMapper] {
      override def load(config: PluginConfig): AutoTuningMapper = config match {
        case autoConfig: AutoTuningMapperConfig => new AutoTuningMapper(autoConfig)
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
  halfTolerance: Double = PitchClassConfig.DEFAULT_HALF_TOLERANCE
) extends TuningMapperConfig

class AutoTuningMapperException(message: String) extends TuningMapperException(message, null)
