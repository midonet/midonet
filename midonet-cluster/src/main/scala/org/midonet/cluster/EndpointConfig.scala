/*
 *  Copyright (c) 2016 Midokura SARL
 */

package org.midonet.cluster

import java.io.File
import java.net.InetAddress
import java.util.Properties

import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.util.Try

import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigException}

import org.midonet.cluster.EndpointConfig.SSLSource.SSLSource
import org.midonet.cluster.services.endpoint.EndpointDescriptor

/**
  * Configuration for Endpoint service.
  */
class EndpointConfig(val conf: Config,
                     systemProperties: Properties = System.getProperties) {
    import EndpointConfig.{DefaultKeystoreLocation, GlobalPrefix, SSLSource, optionString}

    /** Is the endpoint service enabled? */
    def enabled: Boolean = conf.getBoolean(s"$GlobalPrefix.enabled")

    /** Advertised host for the service */
    def serviceHost: Option[String] =
        optionString(conf, s"$GlobalPrefix.service.host").orElse {
            Try(InetAddress.getLocalHost.getHostAddress).toOption
        }

    /** Advertised port for the service */
    def servicePort: Int = conf.getInt(s"$GlobalPrefix.service.port")

    /** Interface to which we should bind the endpoint service */
    def serviceInterface: Option[String] =
        optionString(conf, s"$GlobalPrefix.service.interface")

    /** Whether SSL is enabled or not */
    def authSslEnabled: Boolean =
        conf.getBoolean(s"$GlobalPrefix.auth.ssl.enabled")

    /** Source for SSL context. Defaults to self-signed certificate */
    def sslSource: SSLSource =
        SSLSource(optionString(conf, s"$GlobalPrefix.auth.ssl.source"))

    /** Location of keystore for SSL auth using keystore */
    def keystoreLocation: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.keystore_path",
            systemProperties.getProperty("midonet.keystore_path",
                                         DefaultKeystoreLocation))

    /** Keystore password for SSL auth using keystore */
    def keystorePassword: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.keystore_password",
            systemProperties.getProperty("midonet.keystore_password"))

    /** Location of certificate for SSL auth using certificate */
    def certificateLocation: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.certificate_path",
            systemProperties.getProperty("midonet.certificate_path"))

    /** Location of private key for SSL auth using certificate */
    def privateKeyLocation: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.privatekey_path",
            systemProperties.getProperty("midonet.privatekey_path"))

    /** Password of the private key for SSL auth using certificate */
    def privateKeyPassword: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.privatekey_password",
            systemProperties.getProperty("midonet.privatekey_password"))
}

object EndpointConfig {
    final val LocalPrefix = "endpoint"
    final val GlobalPrefix = createPrefixFromParent("cluster",
                                                    LocalPrefix)

    final val DefaultKeystoreLocation = "/etc/midonet-cluster/ssl/midonet.jks"

    object SSLSource extends Enumeration {
        type SSLSource = Value
        val AutoSigned = Value("autosigned")
        val Keystore = Value("keystore")
        val Certificate = Value("certificate")

        def apply(str: Option[String]) = {
            SSLSource.withName(str.getOrElse(AutoSigned.toString))
        }
    }

        /** Create a new prefix based on a parent prefix and the current one */
    def createPrefixFromParent(parentPrefix: String, prefix: String): String =
        (if (parentPrefix == null) "" else parentPrefix + ".") + prefix

    /** Get a string if the configuration entry is not empty, or from
      * the specified alternative source otherwise (default to null and thus
      * None as a result) */
    def optionString(conf: Config, path: String,
                     alternativeSrc: => String = null): Option[String] = {
        lazy val alternativeOption = Option(alternativeSrc).filter(_.nonEmpty)

        if (conf.hasPath(path))
            Some(conf.getString(path)).filter(_.nonEmpty)
                .orElse(alternativeOption)
        else
            alternativeOption
    }

    /** Get a host and port spec, if not empty */
    def optionHostAndPort(conf: Config, path: String): Option[HostAndPort] =
        if (conf.hasPath(path)) conf.getString(path) match {
            case empty: String if empty.isEmpty => None
            case hostPort => Some(HostAndPort.fromString(hostPort))
        } else None

    /** Get a file if the configuration entry points to an existing directory.
      * If no directory is provided in the configuration but alternative
      * directories are provided, return the first one of those that exists.
      * If no directories exist, return None.
      *
      * NOTE: If a directory is provided by the configuration, the default
      *       directories WILL NOT be tried, even if the provided directory
      *       does not exist.
      *
      * @param conf Configuration to read from.
      * @param path Path to the configuration entry whose value we want to read.
      * @param alternativeDirs Alternative directories to check if one is not
      *                        provided.
      * @return An option containing the first existing directory found or None
      *         if no directory exists.
      */
    def optionExistingDir(conf: Config, path: String,
                          alternativeDirs: List[String] = Nil)
    : Option[File] = {
        optionString(conf, path).fold(alternativeDirs.toStream)(p => Stream(p))
            .map(new File(_)).find(_.isDirectory)
    }

    /** Get a list of host and port specs, or an empty list */
    def listHostAndPort(conf: Config, path: String): List[HostAndPort] =
        if (conf.hasPath(path)) try {
            conf.getStringList(path).asScala.filterNot(_.isEmpty)
                .map(HostAndPort.fromString).toList
        } catch {
            case e: ConfigException.WrongType if conf.getString(path).isEmpty =>
                List.empty
        } else
            List.empty

    /** Get an endpoint descriptor from an endpoint description string */
    def endpointDescriptor(conf: Config, path: String)
    : Option[EndpointDescriptor] =
        optionString(conf, path) match {
            case None => None
            case Some(empty) if empty.isEmpty => None
            case nonEmpty => EndpointDescriptor(nonEmpty)
        }
}
