package org.calinburloiu.music.microtuner

import javax.sound.midi.Receiver

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.tuning.Tuning

trait Tuner {

  def tune(tuning: Tuning, baseNote: Int = 0): Unit
}

trait LoggerTuner extends Tuner with StrictLogging {
  import org.calinburloiu.music.tuning.PianoKeyboardTuningUtils._

  abstract override def tune(tuning: Tuning, baseNote: Int = 0): Unit = {
    logger.info(s"Tuning to ${tuning.toPianoKeyboardString}")

    super.tune(tuning)
  }
}

class MidiTuner(
  val receiver: Receiver,
  val tuningFormat: MidiTuningFormat,
) extends Tuner {

  private val tuningMessageGenerator = tuningFormat.messageGenerator

  override def tune(tuning: Tuning, baseNote: Int = 0): Unit = {
    val sysexMessage = tuningMessageGenerator.generate(tuning)
    receiver.send(sysexMessage, -1)
  }

  // TODO Rethink which code component has the transpose responsibility
  def transpose(tuningValues: Array[Double], baseNote: Int): Array[Double] = {
    Stream.range(0, 12)
      .map { index =>
        val transposedIndex = (index + baseNote) % 12
        tuningValues(transposedIndex)
      }
      .toArray
  }
}
