package org.calinburloiu.music.microtonalist.tuner

trait BaseTrackIO {
  /**
   * [[Some]] MIDI channel number if that particular channel must be used, or [[None]] if ''any'' channel can be used.
   *
   * The channel number must be between 0 and 15, inclusive.
   */
  val channel: Option[Int]
}

trait TrackInput extends BaseTrackIO

trait TrackOutput extends BaseTrackIO

trait TrackIO extends TrackInput with TrackOutput
