package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.microtonalist.sync.MicrotunerEvent

case class TuningChangedEvent(tuningIndex: Int, oldTuningIndex: Int) extends MicrotunerEvent
