package org.calinburloiu.music.microtuner

import org.calinburloiu.music.tuning.{Tuning, TuningList}

class TuningSwitch(
  val tuner: Tuner,
  val tuningList: TuningList,
  initialPosition: Int = 0
) {

  private[this] var _currentPosition: Int = initialPosition

  tuner.tune(currentTuning)

  def apply(index: Int): Unit = {
    if (_currentPosition > tuningList.tunings.size - 1) {
      // TODO message
      throw new IllegalArgumentException()
    } else if (index != _currentPosition) {
      _currentPosition = index

      tuner.tune(currentTuning)
    }
  }

  def prev(): Unit = {
    // TODO Use Guava for mod with negatives: IntMath.mod
    if (_currentPosition > 0) {
      _currentPosition -= 1
    } else {
      _currentPosition = tuningList.tunings.size - 1
    }

    tuner.tune(currentTuning)
  }

  def next(): Unit = {
    _currentPosition = (_currentPosition + 1) % tuningList.tunings.size

    tuner.tune(currentTuning)
  }

  def currentPosition: Int = _currentPosition

  def currentTuning: Tuning = tuningList(_currentPosition)
}
