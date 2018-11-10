package org.calinburloiu.music.intonation.io

import com.google.common.net.MediaType

case class FormatIdentifier(
  name: String,
  extensions: Set[String],
  // TODO Using a special object for MediaType would provide validation
  mediaTypes: Set[String] = Set.empty
)
