package org.midonet.midolman.logging

import akka.event._
import akka.event.Logging.{Debug, Info, Warning, Error}
import scala.Error
import org.midonet.midolman.simulation.PacketContext
import akka.event.Logging.Info
import akka.event.Logging.Warning
import akka.event.Logging.Debug
import org.midonet.midolman.rules.ChainPacketContext

object LoggerFactory {

    def getSimulationAwareLog(clazz: Class[_]) (implicit bus: LoggingBus) = {
        new SimulationAwareBusLogging(bus, clazz)
    }

    def getActorSystemThreadLog(clazz: Class[_]) (implicit loggingBus: LoggingBus) = {
        akka.event.Logging(loggingBus, clazz)
    }
}


class SimulationAwareBusLogging(val bus: LoggingBus, val logClass: Class[_]) {

    import Logging._

    def formatSimCookie(implicit context: ChainPacketContext): String = {
        if (context != null) {
            (if (context.flowCookie != None) "[cookie:" else "[genPkt:") +
            context.flowCookie.getOrElse(context.parentCookie.getOrElse("No Cookie")) +
            "]"
        } else {
            ""
        }
    }

    def isErrorEnabled = bus.logLevel >= ErrorLevel
    def isWarningEnabled = bus.logLevel >= WarningLevel
    def isInfoEnabled = bus.logLevel >= InfoLevel
    def isDebugEnabled = bus.logLevel >= DebugLevel

    protected def notifyError(message: String, source: String) { bus.publish(Error(source, logClass, message)) }

    protected def notifyError(cause: Throwable, message: String, source: String) { bus.publish(Error(cause, source, logClass, message)) }

    protected def notifyWarning(message: String, source: String) { bus.publish(Warning(source, logClass, message)) }

    protected def notifyInfo(message: String, source: String) { bus.publish(Info(source, logClass, message)) }

    protected def notifyDebug(message: String, source: String) { bus.publish(Debug(source, logClass, message)) }

    def error(cause: Throwable, message: String)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(cause, message,formatSimCookie) }
    def error(cause: Throwable, template: String, arg1: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(cause, format1(template, arg1),formatSimCookie) }
    def error(cause: Throwable, template: String, arg1: Any, arg2: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2),formatSimCookie) }
    def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3),formatSimCookie) }
    def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3, arg4),formatSimCookie) }

    def error(message: String)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(message,formatSimCookie) }
    def error(template: String, arg1: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(format1(template, arg1), formatSimCookie) }
    def error(template: String, arg1: Any, arg2: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(format(template, arg1, arg2),formatSimCookie) }
    def error(template: String, arg1: Any, arg2: Any, arg3: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3),formatSimCookie) }
    def error(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any)(implicit pktContext: ChainPacketContext) { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4),formatSimCookie) }

    def warning(message: String)(implicit pktContext: ChainPacketContext) { if (isWarningEnabled) notifyWarning(message,formatSimCookie) }
    def warning(template: String, arg1: Any)(implicit pktContext: ChainPacketContext) { if (isWarningEnabled) notifyWarning(format1(template, arg1),formatSimCookie) }
    def warning(template: String, arg1: Any, arg2: Any)(implicit pktContext: ChainPacketContext) { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2),formatSimCookie) }
    def warning(template: String, arg1: Any, arg2: Any, arg3: Any)(implicit pktContext: ChainPacketContext) { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3),formatSimCookie) }
    def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any)(implicit pktContext: ChainPacketContext) { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4),formatSimCookie) }

    def info(message: String)(implicit pktContext: ChainPacketContext) { if (isInfoEnabled) notifyInfo(message,formatSimCookie) }
    def info(template: String, arg1: Any)(implicit pktContext: ChainPacketContext) { if (isInfoEnabled) notifyInfo(format1(template, arg1),formatSimCookie) }
    def info(template: String, arg1: Any, arg2: Any)(implicit pktContext: ChainPacketContext) { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2),formatSimCookie) }
    def info(template: String, arg1: Any, arg2: Any, arg3: Any)(implicit pktContext: ChainPacketContext) { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3),formatSimCookie) }
    def info(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any)(implicit pktContext: ChainPacketContext) { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3, arg4),formatSimCookie) }

    def debug(message: String)(implicit pktContext: ChainPacketContext) { if (isDebugEnabled) notifyDebug(message,formatSimCookie) }
    def debug(template: String, arg1: Any)(implicit pktContext: ChainPacketContext) { if (isDebugEnabled) notifyDebug(format1(template, arg1),formatSimCookie) }
    def debug(template: String, arg1: Any, arg2: Any)(implicit pktContext: ChainPacketContext) { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2),formatSimCookie) }
    def debug(template: String, arg1: Any, arg2: Any, arg3: Any)(implicit pktContext: ChainPacketContext) { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3),formatSimCookie) }
    def debug(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any)(implicit pktContext: ChainPacketContext) { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3, arg4),formatSimCookie) }

    def log(level: Logging.LogLevel, message: String)(implicit pktContext: ChainPacketContext) { if (isEnabled(level)) notifyLog(level, message,formatSimCookie) }
    def log(level: Logging.LogLevel, template: String, arg1: Any)(implicit pktContext: ChainPacketContext) { if (isEnabled(level)) notifyLog(level, format1(template, arg1),formatSimCookie) }
    def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any)(implicit pktContext: ChainPacketContext) { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2),formatSimCookie) }
    def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any)(implicit pktContext: ChainPacketContext) { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3),formatSimCookie) }
    def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any)(implicit pktContext: ChainPacketContext) { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3, arg4),formatSimCookie) }

    final def isEnabled(level: Logging.LogLevel): Boolean = level match {
        case Logging.ErrorLevel   ⇒ isErrorEnabled
        case Logging.WarningLevel ⇒ isWarningEnabled
        case Logging.InfoLevel    ⇒ isInfoEnabled
        case Logging.DebugLevel   ⇒ isDebugEnabled
    }

    final def notifyLog(level: Logging.LogLevel, message: String, source: String): Unit = level match {
        case Logging.ErrorLevel   ⇒ if (isErrorEnabled) notifyError(message, source)
        case Logging.WarningLevel ⇒ if (isWarningEnabled) notifyWarning(message, source)
        case Logging.InfoLevel    ⇒ if (isInfoEnabled) notifyInfo(message, source)
        case Logging.DebugLevel   ⇒ if (isDebugEnabled) notifyDebug(message, source)
    }

    private def format1(t: String, arg: Any) = arg match {
        case a: Array[_] if !a.getClass.getComponentType.isPrimitive ⇒ format(t, a: _*)
        case a: Array[_] ⇒ format(t, (a map (_.asInstanceOf[AnyRef]): _*))
        case x ⇒ format(t, x)
    }

    def format(t: String, arg: Any*) = {
        val sb = new StringBuilder
        var p = 0
        var rest = t
        while (p < arg.length) {
            val index = rest.indexOf("{}")
            if (index == -1) {
                sb.append(rest).append(" WARNING arguments left: ").append(arg.length - p)
                rest = ""
                p = arg.length
            } else {
                sb.append(rest.substring(0, index))
                sb.append(arg(p))
                rest = rest.substring(index + 2)
                p += 1
            }
        }
        sb.append(rest)
        sb.toString
    }

}
