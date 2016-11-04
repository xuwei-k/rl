package rl

import io.{Codec, Source}

trait UriHostDomains { self: UriHost ⇒
  protected def parsed: (String, String, String)

  def publicSuffix = parsed._1
  def domain: String = parsed._2
  def subdomain: String = parsed._3
}
object DomainParser {

  lazy val publicSuffixes = {
    val src = Source.fromInputStream(DomainParser.getClass.getResourceAsStream("/rl/tld_names.dat"))(Codec.UTF8)
    src.getLines.foldLeft(PublicSuffixList.empty) { (buff, line) ⇒
      line.blankOption filter (l ⇒ !l.startsWith("//")) map { l ⇒
        val parts = l.split("\\.").reverse
        parts.foldLeft(buff) (_ :+ _)
        buff
      } getOrElse buff
    }
  }

  object PublicSuffixList {
    def empty = new PublicSuffixList(Vector.empty[PublicSuffix])
  }

  class PublicSuffixList(protected var suffixes: Vector[PublicSuffix]) {

    def get(key: String) = suffixes find (_.key == key) map (_.values)
    def apply(key: String) = get(key).get
    def contains(key: String) = get(key).isDefined
    def notContains(key: String) = get(key).isEmpty
    def isEmpty = suffixes.isEmpty
    private[DomainParser] def :+(key: String) = get(key) getOrElse {
      val suff = PublicSuffix(key)
      suffixes = suffixes :+ suff
      suff.values
    }

    override def toString = suffixes.toString()
  }
  case class PublicSuffix(key: String, values: PublicSuffixList = PublicSuffixList.empty)

  def apply(uri: String) = {
    val parts = (uri split "\\.").reverse
    val part = parts.head
    val subParts = publicSuffixes get part getOrElse PublicSuffixList.empty
    if (subParts contains "*") {
      (part + "." + parts(1), parts(2), parts.slice(3, parts.size) mkString ".")
    } else if (subParts.isEmpty || subParts.notContains(parts(1))) {

      (part, if (parts.size > 1) parts(1) else "", if (parts.size > 2) parts.slice(2, parts.size) mkString "." else "")
    } else {
      (part, "", "")
    }
  }
}
