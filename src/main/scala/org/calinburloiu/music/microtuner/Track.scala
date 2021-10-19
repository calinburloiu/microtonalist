package org.calinburloiu.music.microtuner

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.music.microtuner.midi.{MidiPipeline, TuningSwitchProcessor}

import javax.sound.midi.{MidiMessage, Receiver}

class Track(tuningSwitchProcessor: Option[TuningSwitchProcessor],
            tuner: Tuner,
            outputReceiver: Receiver) extends Receiver with StrictLogging {
  val pipeline: MidiPipeline = new MidiPipeline(Seq(tuningSwitchProcessor, Some(tuner)).flatten, outputReceiver)

  override def send(message: MidiMessage, timeStamp: Long): Unit = {
    pipeline.send(message, timeStamp)
  }

  override def close(): Unit = logger.info(s"Closing ${this.getClass.getCanonicalName}...")
}
