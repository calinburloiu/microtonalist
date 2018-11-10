package org.calinburloiu.music.intonation.io

import scala.util.Try

trait RefResolver[+A] {

  def get(uri: String, mediaType: Option[String] = None): A

  def getOption(uri: String, mediaType: Option[String] = None): Option[A] = Try {
    get(uri, mediaType)
  }.toOption
}
