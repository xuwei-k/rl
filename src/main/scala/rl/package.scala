import scalax.io.Codec

package object rl {

  class UriStringExtensions(source: String) {
    def isNotBlank = Option(source).foldLeft(false) { (_, v) ⇒ v.trim.nonEmpty }
    def blankOpt = if (isNotBlank) Some(source) else None
  }

  class RicherUriString(source: String) {
    def urlEncode = if (source != null && !source.trim.isEmpty) UrlCodingUtils.urlEncode(source) else ""
    def urlDecode = if (source != null && !source.trim.isEmpty) UrlCodingUtils.urlDecode(source) else ""
  }

  private[rl] implicit def string2UriStringExtension(source: String) = new UriStringExtensions(source)
  private[rl] implicit val Utf8Codec = Codec.UTF8

  implicit def string2RicherUriString(s: String) = new RicherUriString(s)
}