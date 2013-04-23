package rl
package expand

import com.ning.http.client._
import java.util.{ concurrent => juc }
import java.util.concurrent.atomic.AtomicLong
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig
import scala.util.control.Exception._
import filter.{ResponseFilter, FilterContext}
import com.ning.http.client.AsyncHandler.STATE
import scala.concurrent.duration._
import scala.concurrent.{Future, ExecutionContext, Promise}
import org.slf4j.LoggerFactory

case class ExpanderConfig(
             maximumResolveSteps: Int = 15,
             maxConnectionsTotal: Int = 200,
             maxConnectionsPerHost: Int = 5,
             threadPoolSize: Int = 200,
             requestTimeout: Duration = 30.seconds,
             userAgent: String = "Scalatra RL Expander/%s".format(BuildInfo.version))

object UrlExpander {

  private object ActiveRequestThreads {
    private[this] val threadIds = new AtomicLong()
    /** produces daemon threads that won't block JVM shutdown */
    def factory = new juc.ThreadFactory {
      def newThread(runnable: Runnable): Thread = {
        val thread = new Thread(runnable)
        thread.setName("url-expander-thread-" + threadIds.incrementAndGet())
        thread.setDaemon(true)
        thread
      }
    }
    def apply(threadPoolSize: Int) =
      juc.Executors.newFixedThreadPool(threadPoolSize, factory)
  }

  private val RedirectCodes = Vector(301, 302, 303, 307)

  private class PromiseHandler(var current: String, var count: Int, val max: Int, val promise: Promise[Uri], val onRedirect: Uri => Unit) extends AsyncHandler[Uri] {

    var seen404 = false
    def onThrowable(t: Throwable) { promise failure t }
    def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = STATE.CONTINUE
    def onStatusReceived(responseStatus: HttpResponseStatus): STATE = STATE.CONTINUE
    def onHeadersReceived(headers: HttpResponseHeaders): STATE = STATE.CONTINUE
    def onCompleted(): Uri = {
      val uu = rl.Uri(current)
      promise success uu
      uu
    }

    def canRedirect = count < max
  }

  object RedirectsExhausted {
    def apply(curr: String, max: Int): RedirectsExhausted = new RedirectsExhausted(curr, max)
  }
  class RedirectsExhausted(val current: String, max: Int) extends Exception("The maximum number of redirects ["+max+"] has been reached.")

  object DestinationNotFound {
    def apply(curr: String, max: Int): RedirectsExhausted = new RedirectsExhausted(curr, max)
  }
  class DestinationNotFound(val current: String) extends Exception("The final url [%s] no longer exists." format current)

  private class RedirectFilter extends ResponseFilter {
    @transient private[this] val logger = LoggerFactory.getLogger("rl.expand.RedirectFilter")
    def filter(ctx: FilterContext[_]): FilterContext[_] = {
      ctx.getAsyncHandler match {
        case h: PromiseHandler if RedirectCodes.contains(ctx.getResponseStatus.getStatusCode) && h.canRedirect =>
          h.seen404 = false
          h.onRedirect(rl.Uri(h.current))
          h.count += 1

          val newUri = ctx.getResponseHeaders.getHeaders.getFirstValue("Location")
          h.current = rl.Uri(rl.UrlCodingUtils.ensureUrlEncoding(newUri)).normalize.asciiStringWithoutTrailingSlash
          if (logger.isDebugEnabled) logger.debug("Received a redirect, going to %s.".format(newUri))

          (new FilterContext.FilterContextBuilder[Uri]()
            asyncHandler h
            request new RequestBuilder(ctx.getRequest.getMethod, true).setUrl(h.current).build()
            replayRequest true).build()

        case h: PromiseHandler if RedirectCodes.contains(ctx.getResponseStatus.getStatusCode) =>
          throw RedirectsExhausted(h.current, h.count)

        case h: PromiseHandler if ctx.getResponseStatus.getStatusCode == 404 && !h.seen404 =>
          if (logger.isDebugEnabled) logger.debug("Received a 404, retrying with a trailing slash.")
          h.seen404 = true
          val newUri = h.current + "/"
          h.current = newUri
          (new FilterContext.FilterContextBuilder[Uri]()
            asyncHandler h
            request new RequestBuilder("GET", true).setUrl(newUri).build()
            replayRequest true).build()

        case h: PromiseHandler if ctx.getResponseStatus.getStatusCode == 404 =>
          throw new DestinationNotFound(h.current)

        case h: PromiseHandler =>
          if (logger.isDebugEnabled)
            logger.debug("The expander got a promise handler but the status code is [" + ctx.getResponseStatus.getStatusCode +"].")
          ctx

        case _ =>
          if (logger.isWarnEnabled) logger.warn("The expander got a handler that's not a promise handler.")
          ctx
      }
    }
  }



  def apply(config: ExpanderConfig = ExpanderConfig()) = new UrlExpander(config)
}

final class UrlExpander(config: ExpanderConfig = ExpanderConfig()) {

  import UrlExpander._
  protected implicit val execContext = ExecutionContext.fromExecutorService(ActiveRequestThreads(config.threadPoolSize))
  private[this] val bossExecutor = juc.Executors.newCachedThreadPool(ActiveRequestThreads.factory)
  private[this] val clientExecutor = juc.Executors.newCachedThreadPool(ActiveRequestThreads.factory)

  private[this] val httpConfig =
    (new AsyncHttpClientConfig.Builder()
      setAllowPoolingConnection false
      setFollowRedirects false
      setExecutorService clientExecutor
      addResponseFilter new RedirectFilter
      setMaximumConnectionsPerHost config.maxConnectionsPerHost
      setMaximumConnectionsTotal config.maxConnectionsTotal
      setRequestTimeoutInMs config.requestTimeout.toMillis.toInt
      setUserAgent config.userAgent
      setAsyncHttpClientProviderConfig (
        new NettyAsyncHttpProviderConfig().addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE, bossExecutor))).build()


  private[this] val http = new AsyncHttpClient(httpConfig)
  sys.addShutdownHook(stop())

  def apply(uri: String): Future[rl.Uri] = apply(rl.Uri(uri), (_: rl.Uri) => ())
  def apply(uri: String, onRedirect: Uri => Unit): Future[rl.Uri] = apply(rl.Uri(uri), onRedirect)
  def apply(uri: rl.Uri, onRedirect: Uri => Unit = _ => ()): Future[rl.Uri] = {
    val prom = scala.concurrent.Promise[rl.Uri]()
    val u = uri.normalize.asciiStringWithoutTrailingSlash
    val req = http.prepareGet(u)
    req.execute(new PromiseHandler(u, 0, config.maximumResolveSteps, prom, onRedirect))
    prom.future
  }

  def stop() {
    val shutdownThread = new Thread {
      override def run() {
        allCatch { http.close() }
        allCatch { clientExecutor.shutdownNow() }
        allCatch { bossExecutor.shutdownNow() }
        allCatch { execContext.shutdownNow() }
        allCatch { clientExecutor.awaitTermination(30, juc.TimeUnit.SECONDS) }
        allCatch { bossExecutor.awaitTermination(30, juc.TimeUnit.SECONDS) }
        allCatch { execContext.awaitTermination(30, juc.TimeUnit.SECONDS) }
      }
    }
    shutdownThread.setDaemon(false)
    shutdownThread.start()
    shutdownThread.join()
  }

}