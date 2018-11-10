package org.calinburloiu.music.intonation.io

import java.io.OutputStream

import org.calinburloiu.music.intonation.{Interval, Scale}


trait ScaleWriter {

  def write(scale: Scale[Interval]): OutputStream
}
