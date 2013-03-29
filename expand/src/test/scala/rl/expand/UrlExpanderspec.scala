package rl
package expand

import org.jboss.netty.channel.{ChannelFutureListener, ChannelHandlerContext}
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.codec.http.HttpHeaders.Names
import org.jboss.netty.buffer.ChannelBuffers
import akka.dispatch.Await
import akka.util.duration._
import org.specs2.time.NoTimeConversions

class UrlExpanderspec extends org.specs2.mutable.Specification with NoTimeConversions  {
//  def nettyContext: NettyHttpServerContext = new NettyHttpServerContext {
//    def handleRequest(ctx: ChannelHandlerContext, req: HttpRequest) {
//
//    }
//  }

//  override def intToRichLong(v: Int)   = super.intToRichLong(v)
//  override def longToRichLong(v: Long) = super.longToRichLong(v)

  sequential


  "A UrlExpander" should {
    "expand urls that redirect with a 302 status" in {
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

    "expand urls that redirect with a 301 status" in {
      var count = 0
      val server = new NettyHttpServerContext {
        def handleRequest(ctx: ChannelHandlerContext, req: HttpRequest) {
          if (count < 3) {
            count += 1
            val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.MOVED_PERMANENTLY)
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

    "throw an error when the max redirects are done" in {
      var count = 0
      val server = new NettyHttpServerContext {
        def handleRequest(ctx: ChannelHandlerContext, req: HttpRequest) {
          if (count < 3) {
            count += 1
            val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.MOVED_PERMANENTLY)
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
      val expand = UrlExpander(ExpanderConfig(maximumResolveSteps = 1))
      try {
        Await.result(expand(Uri("http://127.0.0.1:"+server.port+"/")), 5 seconds) must throwA[UrlExpander.RedirectsExhausted]
        count must_== 2
      } finally {
        server.stop
        expand.stop()
      }
    }
  }
}