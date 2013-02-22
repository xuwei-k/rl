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

case class ExpanderConfig(
             maxConnectionsTotal: Int = 50,
             maxConnectionsPerHost: Int = 5,
             threadPoolSize: Int = 50,
             requestTimeout: Duration = 90.seconds)

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

  private val RedirectCodes = Vector(301, 302)

  private class PromiseHandler(var current: Uri, val promise: Promise[Uri], val onRedirect: Uri => Unit) extends AsyncHandler[Uri] {
    def onThrowable(t: Throwable) { promise failure t }
    def onBodyPartReceived(bodyPart: HttpResponseBodyPart): STATE = STATE.CONTINUE
    def onStatusReceived(responseStatus: HttpResponseStatus): STATE = STATE.CONTINUE
    def onHeadersReceived(headers: HttpResponseHeaders): STATE = STATE.CONTINUE
    def onCompleted(): Uri = { promise success current; current }
  }

  private class RedirectFilter extends ResponseFilter {
    def filter(ctx: FilterContext[_]): FilterContext[_] = {
      ctx.getAsyncHandler match {
        case h: PromiseHandler if RedirectCodes contains ctx.getResponseStatus.getStatusCode =>
          h.onRedirect(h.current)
          h.current = rl.Uri(ctx.getResponseHeaders.getHeaders.getFirstValue("Location")).normalize
          (new FilterContext.FilterContextBuilder[Uri]()
            asyncHandler h
            request new RequestBuilder("GET", true).setUrl(h.current.asciiString).build()
            replayRequest true).build()
        case _ => ctx
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
      setAllowPoolingConnection true
      setFollowRedirects false
      setExecutorService clientExecutor
      addResponseFilter new RedirectFilter
      setMaximumConnectionsPerHost config.maxConnectionsPerHost
      setMaximumConnectionsTotal config.maxConnectionsTotal
      setRequestTimeoutInMs config.requestTimeout.toMillis.toInt
      setAsyncHttpClientProviderConfig (
        new NettyAsyncHttpProviderConfig().addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE, bossExecutor))).build()


  private[this] val http = new AsyncHttpClient(httpConfig)
  sys.addShutdownHook(stop())

  def apply(uri: rl.Uri, onRedirect: Uri => Unit = _ => ()): Future[rl.Uri] = {
    val prom = scala.concurrent.Promise[rl.Uri]()
    val u = uri.normalize
    val req = http.prepareGet(u.asciiString)
    req.execute(new PromiseHandler(u, prom, onRedirect))
    prom.future
  }

  def stop() {
    val shutdownThread = new Thread {
      override def run() {
        allCatch { clientExecutor.shutdownNow() }
        allCatch { bossExecutor.shutdownNow() }
        allCatch { execContext.shutdownNow() }
        allCatch { clientExecutor.awaitTermination(1, juc.TimeUnit.MINUTES) }
        allCatch { bossExecutor.awaitTermination(1, juc.TimeUnit.MINUTES) }
        allCatch { execContext.awaitTermination(1, juc.TimeUnit.MINUTES) }
      }
    }
    shutdownThread.setDaemon(false)
    shutdownThread.start()
    shutdownThread.join()
  }

}