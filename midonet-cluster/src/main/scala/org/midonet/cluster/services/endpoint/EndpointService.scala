/*
 * Copyright 2017 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.endpoint

import java.io.{BufferedInputStream, File, FileInputStream, InputStream}
import java.net.{BindException, InetAddress, InetSocketAddress}
import java.security.cert.CertificateException
import java.security.{InvalidKeyException, KeyStore, KeyStoreException}
import java.util.concurrent._

import javax.net.ssl.KeyManagerFactory

import scala.async.Async.async
import scala.collection.JavaConverters.asScalaSetConverter
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.Try
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.google.inject.{AbstractModule, Inject, Injector}

import org.reflections.Reflections
import org.slf4j.LoggerFactory

import org.midonet.cluster.EndpointConfig.SSLSource

import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.ssl.{SslContext, SslContextBuilder}
import org.midonet.cluster.{ClusterConfig, EndpointConfig}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.endpoint.comm.BaseEndpointAdapter
import org.midonet.cluster.services.endpoint.registration.EndpointUserRegistrar
import org.midonet.cluster.services.endpoint.users.EndpointUser
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.util.concurrent.{NamedThreadFactory, ThreadHelpers}
import org.midonet.util.netty.ServerFrontEnd


/**
  * Endpoint provider service
  *
  * This service provides a central common endpoint for accessing functionality
  * of other services. Messages received by it are redirected to the appropriate
  * registered service (if any) based on the URL path.
  *
  * @param injector Guice injector that created this service and, consequently,
  *                 all other services. Until such a time as we have some
  *                 explicit way to communicate with other services in the same
  *                 cluster we need this *hack* to access their instances.
  */
@MinionService(name = "endpoint", runsOn = TargetNode.CLUSTER)
class EndpointService @Inject() (nodeCtx: Context, conf: ClusterConfig,
                                 backend: MidonetBackend,
                                 metrics: MetricRegistry,
                                 injector: Injector,
                                 reflections: Reflections)
    extends Minion(nodeCtx) {

    import EndpointService.{ServiceDisabledException, ServiceName, TimeoutUserReg}

    private val log = LoggerFactory.getLogger(classOf[EndpointService])
    private val graceTime = Duration(5, TimeUnit.SECONDS)
    private val eConfig = new EndpointConfig(conf.conf)

    // Create a thread pool with maximum of 5 threads. These threads will be
    // destroyed after 3 idle seconds and recreated on-demand.
    private[endpoint] val userRegistrationExecutor =
        new ThreadPoolExecutor(5, 5, 3, TimeUnit.SECONDS,
                               new LinkedBlockingQueue[Runnable],
                               new NamedThreadFactory(
                                   ServiceName + "-discovery", isDaemon = true))
    userRegistrationExecutor.allowCoreThreadTimeOut(true)
    private val userRegistrationExecutionCtx =
        ExecutionContext.fromExecutor(userRegistrationExecutor)

    private[endpoint] val userRegistrar =
        new EndpointUserRegistrar(eConfig, backend.discovery)

    private val userInjector = createUserInjector()

    private val srv = createServer()

    override def isEnabled: Boolean = eConfig.enabled

    override def doStart(): Unit = {
        log.info("Service {} starting", ServiceName)
        try {
            dumpConfig(eConfig)

            if (isEnabled) {
                // Api service start
                srv.startAsync().awaitRunning()

                // Find and register endpoint users
                registerEndpointUsers()

                notifyStarted()
                log.info("Service {} ready", ServiceName)
            } else {
                log.info("Service {} is disabled", ServiceName)
                terminateAll()
                notifyFailed(ServiceDisabledException())
            }
        } catch {
            case e: IllegalStateException
                if e.getCause.isInstanceOf[BindException] =>
                log.error("Service {} cannot start: " + e.getCause.getMessage,
                          ServiceName)
                notifyFailed(e.getCause)
                terminateAll()
            case NonFatal(e) =>
                log.error(s"Service $ServiceName failed to start", e)
                notifyFailed(e)
                terminateAll()
        }
    }

    override def doStop(): Unit = {
        log.info("Service {} terminating", ServiceName)
        try {
            terminateAll()
            notifyStopped()
            log.info("Service {} terminated", ServiceName)
        } catch {
            case NonFatal(e) =>
                log.warn(s"Service $ServiceName failed to stop", e)
                notifyFailed(e)
        }
    }

    private def terminateAll(): Unit = {
        if (srv.isRunning)
            srv.stopAsync().awaitTerminated(graceTime.toMillis,
                                            TimeUnit.MILLISECONDS)
        userRegistrar.clear()

        ThreadHelpers.terminate(userRegistrationExecutor, graceTime)
    }

    private def createSelfSignedSSLContext() = {
        val cert = new SelfSignedCertificate
        SslContextBuilder.forServer(cert.certificate(),
                                    cert.privateKey()).build()
    }

    private def createKeystoreSSLContext() = {
        val keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm)

        val keystoreLocation = eConfig.keystoreLocation.getOrElse {
            throw new KeyStoreException("Keystore location not provided")
        }

        val keystorePassword = eConfig.keystorePassword.getOrElse {
            log.warn("No password provided for keystore. " +
                     "Will try to continue but will likely fail")
            ""
        }

        val keyStore = if (keystoreLocation.endsWith(".p12")) {
            KeyStore.getInstance("PKCS12")
        } else {
            KeyStore.getInstance("JKS")
        }

        val keyStorePasswordArray = keystorePassword.toCharArray

        var keyStoreInputStream: InputStream = null

        try {
            keyStoreInputStream =
                new BufferedInputStream(new FileInputStream(keystoreLocation))
            keyStore.load(keyStoreInputStream, keyStorePasswordArray)
        } finally {
            if (keyStoreInputStream != null) {
                keyStoreInputStream.close()
            }
        }

        keyManagerFactory.init(keyStore, keyStorePasswordArray)

        SslContextBuilder.forServer(keyManagerFactory).build()
    }

    private def createCertificateSSLContext() = {
        val certificateLocation = eConfig.certificateLocation.getOrElse {
            throw new CertificateException("Certificate location not provided")
        }

        val certificateFile = new File(certificateLocation)

        val privateKeyLocation = eConfig.privateKeyLocation.getOrElse {
            throw new InvalidKeyException("Private key location not provided")
        }

        val privateKeyFile = new File(privateKeyLocation)

        val privateKeyPassword = eConfig.privateKeyPassword.orNull

        SslContextBuilder.forServer(certificateFile,
                                    privateKeyFile,
                                    privateKeyPassword).build()
    }

    private def createSSLContext(): Option[SslContext] = {
        if (eConfig.authSslEnabled) {
            Option(eConfig.sslSource match {
                case SSLSource.AutoSigned => createSelfSignedSSLContext()
                case SSLSource.Keystore => createKeystoreSSLContext()
                case SSLSource.Certificate => createCertificateSSLContext()
                case _ =>
                    log.warn("Unknown SSL source")
                    null
            })
        } else {
            None
        }
    }

    private def createServer(): ServerFrontEnd = {
        val sslCtx = createSSLContext()

        val baseEndpointAdapter =
            new BaseEndpointAdapter(userRegistrar, sslCtx)

        val addr = eConfig.serviceInterface.map(InetAddress.getByName)

        ServerFrontEnd.tcp(baseEndpointAdapter,
                           new InetSocketAddress(addr.orNull,
                                                 eConfig.servicePort))
    }

    private def dumpConfig(conf: EndpointConfig): Unit = {
        log.info("enabled: {}", conf.enabled)
        log.info("advertised address: {}", conf.serviceHost)
        log.info("port: {}", conf.servicePort)
        log.info("ssl enabled: {}", conf.authSslEnabled)
        log.info("ssl source: {}", conf.sslSource)

        conf.sslSource match {
            case SSLSource.Certificate =>
                log.info("ssl certificate path: {}", conf.certificateLocation)
                log.info("ssl private key path: {}", conf.privateKeyLocation)
            case SSLSource.Keystore =>
                log.info("ssl keystore path: {}", conf.keystoreLocation)
            case _ =>
        }
    }

    /**
      * Asks all enabled endpoint service users to register their channels with
      * this service.
      */
    protected def registerEndpointUsers() = {
        enabledEndpointUsers.foreach(u => registerUser(u))
    }

    /**
      * Creates a child injector requiring explicit bindings for use by user
      * registration.
      *
      * By requiring explicit bindings, we ensure that only those Minion
      * services explicitly considered and bound as Singletons by the
      * ClusterNode will be considered by us. Without this, we could implicitly
      * instantiate some user that could be automatically created with the
      * current binding context but that had not been bound by the ClusterNode.
      *
      * @return
      */
    protected def createUserInjector() = {
        injector.createChildInjector(new AbstractModule {
            override def configure(): Unit = {
                binder().requireExplicitBindings()
            }
        })
    }

    /**
      * Find enabled endpoint users running under the same cluster instance as
      * this endpoint service.
      *
      * We do this by finding all classes that implement EndpointUser
      * (which by definition must also implement Minion) and keeping only those
      * for which the ClusterNode created an explicit singleton binding. From
      * the remaining classes we then get their instances and keep only those
      * that were deemed as enabled by the configuration.
      *
      * @todo Coordinate with core to implement some non-hackish way of querying
      *       the cluster node for other active minions and some mechanism to be
      *       able to reach them.
      *
      * @return List of enabled endpoint user instances residing on the cluster.
      */
    protected def enabledEndpointUsers: List[EndpointUser] = {
        reflections.getSubTypesOf(classOf[EndpointUser]).asScala.toList
            .filter(c => Try(userInjector.getProvider(c)).isSuccess)
            .map(c => injector.getInstance(c))
            .filter(_.isEnabled)
    }

    /**
      * Registers a user as soon as we detect it has started.
      *
      * NOTE: This is done asynchronously because there's no ordering guarantee
      * regarding initialization of minions. Ideally the endpoint could have
      * been loaded at the end so that everyone would already be running. As it
      * is, some will already be running and some won't have started yet so we
      * have to wait for those.
      *
      * @param user An EndpointUser to register.
      */
    protected def registerUser(user: EndpointUser) = async {
        log.info("Found endpoint user: '{}'. Waiting for it", user.getClass)
        try {
            user.awaitRunning(TimeoutUserReg.length, TimeoutUserReg.unit)
            log.info("Registering user '{}'", user.getClass)
            userRegistrar.register(user)
            log.info("'{}' registered", user.getClass)
        } catch {
            case e: TimeoutException =>
                log.warn("Timed out while waiting for endpoint user '{}' " +
                         "to start", user)
            case e: Throwable =>
                log.error(s"Error while registering user '$user'.", e)
        }
    }(userRegistrationExecutionCtx)
}

object EndpointService {
    final val ServiceName = "Endpoint"
    final val TimeoutUserReg = Duration.apply(1, TimeUnit.MINUTES)

    case class ServiceDisabledException()
        extends Exception("Service " + ServiceName + "is disabled")
}
