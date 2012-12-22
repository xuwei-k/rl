package rl
package expand

import org.jboss.netty.channel.{ChannelFutureListener, ChannelHandlerContext}
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.codec.http.HttpHeaders.Names
import org.jboss.netty.buffer.ChannelBuffers
import org.specs2.time.NoTimeConversions
import scala.concurrent.duration._
import scala.concurrent.Await

class UrlExpanderspec extends org.specs2.mutable.Specification with NoTimeConversions {
//  def nettyContext: NettyHttpServerContext = new NettyHttpServerContext {
//    def handleRequest(ctx: ChannelHandlerContext, req: HttpRequest) {
//
//    }
//  }

  sequential


  "A UrlExpander" should {
    "expand urls" in {
      var count = 0
      val server = new NettyHttpServerContext {
        def handleRequest(ctx: ChannelHandlerContext, req: HttpRequest) {
          if (count < 3) {
            count += 1
            val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND)
            resp.setHeader(Names.CONTENT_TYPE, "text/plain")
            resp.setHeader(HttpHeaders.Names.LOCATION, "http://127.0.0.1:"+port+"/"+count)
            resp.setContent(ChannelBuffers.wrappedBuffer("".getBytes("UTF-8")))
            val future = ctx.getChannel.write(resp)
            future addListener ChannelFutureListener.CLOSE
          } else {
            writeResponse(ctx, "done")
          }
        }
      }
      server.start
      val expand = UrlExpander()
      try {
        Await.result(expand(Uri("http://127.0.0.1:"+server.port+"/")), 5 seconds) must_== Uri("http://127.0.0.1:"+server.port+"/3")
        count must be_==(3)
      } finally {
        server.stop
        expand.stop()
      }
    }
  }

  end
}