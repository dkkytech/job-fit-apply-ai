package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Generic RSS 2.0 job feed: Taleo Business Edition (Costco), Avature (lululemon, EA, Slalom,
 * Deloitte), SuccessFactors and NEOGOV. Location comes from whichever location-ish element the
 * platform adds (taleo:location, joblisting:location, …); a blank location passes the filter.
 * When an item has no usable description, [describe] fetches the posting page and reads
 * [Employer.detailSelector] (Avature: `.article__content--rich-text`).
 */
class RssAdapter(private val http: Http) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val feed = requireNotNull(employer.feed) { "rss employer ${employer.company} has no feed" }
        val xml = http.get(feed).trimStart('\uFEFF')
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as Element
            val link = child(item, "link")
            // No guid and no link = no stable id and nowhere to apply: an empty id would collapse every
            // such item onto one idempotency key at the bridge, so drop it instead.
            val id = child(item, "guid").ifBlank { link }.ifBlank { return@mapNotNull null }
            Posting(
                company = employer.company,
                externalId = id,
                title = child(item, "title"),
                location = locationOf(item),
                url = link,
                description = htmlToText(child(item, "description")),
            )
        }
    }

    override fun describe(employer: Employer, posting: Posting): String? {
        val inline = posting.description
        if (inline != null && inline.length >= MIN_INLINE_CHARS) return inline
        val selector = employer.detailSelector ?: return inline
        val page = Jsoup.parse(http.get(posting.url), posting.url)
        val el = page.selectFirst(selector) ?: return inline
        return htmlToText(el.html()) ?: inline
    }

    private fun child(item: Element, name: String): String {
        val nodes = item.getElementsByTagName(name)
        return if (nodes.length == 0) "" else nodes.item(0).textContent.orEmpty().trim()
    }

    /** First child element whose local name looks like a location field, e.g. taleo:location. */
    private fun locationOf(item: Element): String {
        val parts = mutableListOf<String>()
        var n = item.firstChild
        while (n != null) {
            if (n is Element) {
                val local = (n.localName ?: n.nodeName).substringAfter(':').lowercase()
                if (local in LOCATION_FIELDS) n.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
            }
            n = n.nextSibling
        }
        return parts.distinct().joinToString(", ")
    }

    companion object {
        /** Shorter inline descriptions are teasers; the bridge needs >= 150 chars anyway. */
        private const val MIN_INLINE_CHARS = 400
        private val LOCATION_FIELDS = setOf("location", "locationcity", "locationstate", "city", "state")
        private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Feeds are untrusted input: no DTDs, no external entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }
    }
}
