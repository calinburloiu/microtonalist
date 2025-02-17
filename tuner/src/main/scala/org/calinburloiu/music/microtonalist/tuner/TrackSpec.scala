package org.calinburloiu.music.microtonalist.tuner

case class TrackSpec(id: String,
                     name: String,
                     input: TrackInput,
                     tuningChanger: TuningChanger,
                     tuner: Tuner,
                     output: TrackOutput,
                     muted: Boolean = false) {
  require(id != null && id.nonEmpty, "id must not be null or empty!")
  require(name != null && name.nonEmpty, "name must not be null or empty!")
}
