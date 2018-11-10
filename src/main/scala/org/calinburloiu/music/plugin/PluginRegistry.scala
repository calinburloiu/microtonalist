package org.calinburloiu.music.plugin

import scala.util.Try
import scala.util.control.NonFatal

trait PluginRegistry[P <: Plugin] {

  def registeredPluginFactories: Seq[PluginFactory[P]]

  protected val indexedFactories: Map[String, PluginFactory[P]] = {
    val indexedById = registeredPluginFactories.map { factory =>
      (factory.pluginId, factory)
    }.toMap
    val indexedByClassName = registeredPluginFactories.map { factory =>
      (factory.getClass.getName, factory)
    }.toMap

    indexedById ++ indexedByClassName
  }

  @throws(classOf[PluginLocatorException])
  def get(id: String): PluginFactory[P] = {
    val maybeFactory = indexedFactories.get(id)
    maybeFactory.getOrElse {
      getByClassName(id)
    }
  }

  private[this] def getByClassName(fqn: String): PluginFactory[P] = {
    Try {
      val clazz = Class.forName(fqn)
      clazz.newInstance() match {
        case plugin: PluginFactory[P] => plugin
        case _ => throw new PluginLocatorException(
          s"Plugin with ID $fqn does not implement the required interface")
      }
    }.recover {
      case e: ClassNotFoundException => throw new PluginLocatorException(
        s"Plugin with ID $fqn was not found", e)
      case e: NoSuchMethodException => throw new PluginLocatorException(
        s"The config for plugin with ID $fqn is invalid", e)
      case NonFatal(t) => throw new PluginLocatorException(
        s"Cannot instantiate plugin with ID $fqn", t)
    }.get
  }
}

class PluginLocatorException(message: String, cause: Throwable = null)
  extends RuntimeException(message, cause)
