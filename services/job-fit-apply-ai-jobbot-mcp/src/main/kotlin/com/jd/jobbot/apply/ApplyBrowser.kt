package com.jd.jobbot.apply

import com.google.gson.JsonObject
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.LoadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One interactive element on the page, as the fill loop sees it. Passwords are never included. */
@Serializable
data class Element(
    val id: String,
    val kind: String,
    val type: String? = null,
    val label: String = "",
    val name: String? = null,
    val required: Boolean = false,
    val value: String? = null,
    val options: List<String> = emptyList(),
    val checked: Boolean? = null,
    val text: String = "",
    val href: String? = null,
    val inForm: Boolean = false,
    val formFilled: Boolean = false,
    /** A "Sign in with Google" control: the site's own button, or Google's embedded button iframe. */
    val google: Boolean = false,
)

@Serializable
data class Snapshot(
    val url: String,
    val title: String = "",
    val elements: List<Element> = emptyList(),
    val captcha: Boolean = false,
    val text: String = "",
)

/** What a click on a Google sign-in control opened. */
sealed interface GoogleWindow {
    /** Nothing new: the tab itself went to Google (handled on the next snapshot), or nothing happened. */
    data object None : GoogleWindow
    /** Google's sign-in popup window (Google Identity Services buttons open one). */
    class Popup(val page: ApplyPage) : GoogleWindow
    /** Chrome's own FedCM account dialog — browser UI, not a page. [select] picks an account by index. */
    class FedCm(val dialogType: String, val accountEmails: List<String>, val select: (Int) -> Unit) : GoogleWindow
}

/** A browser tab the fill loop drives. Implemented over Playwright; faked in tests. */
interface ApplyPage {
    val url: String
    val isClosed: Boolean get() = false
    fun snapshot(): Snapshot
    fun goto(url: String)
    fun fill(id: String, value: String)
    fun select(id: String, option: String)
    fun check(id: String, checked: Boolean)
    fun upload(id: String, name: String, mime: String, bytes: ByteArray)
    fun click(id: String)
    /** Clicks a Google sign-in control and reports the popup or FedCM dialog it opened, if any. */
    fun clickGoogle(id: String): GoogleWindow { click(id); return GoogleWindow.None }
    fun settle()
    /** Waits [millis] for the page to change by itself (a popup closing, a redirect). */
    fun pause(millis: Long) {}
    fun screenshot(): ByteArray
    fun close()
}

/** Opens tabs in the apply browser. */
fun interface ApplyBrowser {
    fun open(guard: (String) -> Boolean): ApplyPage
}

/**
 * The real apply browser: the long-lived headed Chromium in the `jobbot-browser` container, over
 * CDP. Tabs open in its default (persistent) context, so site logins and the Google session
 * carry over. Every main-frame navigation passes [guard] first — a refused one is aborted at the
 * network layer, so neither the model nor a page script can reach a forbidden site.
 */
class PlaywrightApplyBrowser(private val cdpUrl: String) : ApplyBrowser {
    private val log = LoggerFactory.getLogger(PlaywrightApplyBrowser::class.java)
    private var playwright: Playwright? = null
    private var browser: Browser? = null

    @Synchronized
    private fun context(): BrowserContext {
        val b = browser?.takeIf { it.isConnected } ?: connect()
        return b.contexts().firstOrNull() ?: b.newContext()
    }

    private fun connect(): Browser {
        // Attach-only: never download Playwright's own browsers into this container.
        val pw = playwright ?: Playwright.create(
            Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")),
        ).also { playwright = it }
        val b = pw.chromium().connectOverCDP(wsEndpoint())
        browser = b
        log.info("connected to apply browser {}", b.version())
        return b
    }

    /**
     * DevTools rejects any Host that is not an IP or localhost (DNS-rebinding guard), so the
     * service name is resolved to its address first — the processor's SteelBrowser does the same.
     * See [CdpEndpoint].
     */
    private fun wsEndpoint(): String {
        val base = CdpEndpoint.base(cdpUrl)
        val body = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$base/json/version")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        return CdpEndpoint.wsFromVersionBody(body, base)
    }

    override fun open(guard: (String) -> Boolean): ApplyPage {
        val page = context().newPage()
        page.route("**/*") { route ->
            val req = route.request()
            if (req.isNavigationRequest && req.frame() == page.mainFrame() && !guard(req.url())) {
                log.warn("blocked navigation to {}", req.url())
                route.abort("blockedbyclient")
            } else {
                route.resume()
            }
        }
        return PlaywrightPage(page, guard)
    }

    private class PlaywrightPage(private val page: Page, private val guard: (String) -> Boolean) : ApplyPage {
        private val log = LoggerFactory.getLogger(PlaywrightPage::class.java)
        @Volatile private var fedCmDialog: JsonObject? = null
        private val cdp: CDPSession? by lazy {
            runCatching {
                page.context().newCDPSession(page).also { s ->
                    s.on("FedCm.dialogShown") { ev -> fedCmDialog = ev }
                    s.send("FedCm.enable", JsonObject().apply { addProperty("disableRejectionDelay", true) })
                }
            }.onFailure { log.warn("FedCM watch unavailable: {}", it.message) }.getOrNull()
        }

        override val url: String get() = page.url()
        override val isClosed: Boolean get() = page.isClosed

        override fun snapshot(): Snapshot {
            // A sign-in often ends in a redirect; reading the page mid-navigation destroys the script's context.
            repeat(SNAPSHOT_TRIES - 1) {
                try {
                    return read()
                } catch (e: PlaywrightException) {
                    if (e.message?.contains("context was destroyed") != true && e.message?.contains("navigat") != true) throw e
                    settle()
                }
            }
            return read()
        }

        private fun read(): Snapshot = JSON.decodeFromString(Snapshot.serializer(), page.evaluate(SNAPSHOT_JS) as String)

        override fun goto(url: String) { page.navigate(url); settle() }
        override fun fill(id: String, value: String) { el(id).fill(value) }
        override fun select(id: String, option: String) { el(id).selectOption(option) }
        override fun check(id: String, checked: Boolean) { el(id).setChecked(checked) }
        override fun upload(id: String, name: String, mime: String, bytes: ByteArray) {
            el(id).setInputFiles(FilePayload(name, mime, bytes))
        }
        override fun click(id: String) { el(id).click(); settle() }

        override fun clickGoogle(id: String): GoogleWindow {
            val session = cdp
            fedCmDialog = null
            val popups = java.util.concurrent.LinkedBlockingQueue<Page>()
            val onPopup = java.util.function.Consumer<Page> { popups.add(it) }
            page.onPopup(onPopup)
            try {
                el(id).click()
                // Pumping Playwright (waitForTimeout) is what delivers the popup and CDP events.
                repeat(POPUP_POLLS) {
                    popups.poll()?.let { return popup(it) }
                    val dialog = fedCmDialog
                    if (dialog != null && session != null) return fedCm(session, dialog)
                    if (page.isClosed) return GoogleWindow.None
                    page.waitForTimeout(250.0)
                }
                return GoogleWindow.None
            } finally {
                page.offPopup(onPopup)
            }
        }

        /** The popup gets the same navigation guard; one that opened somewhere forbidden is closed at once. */
        private fun popup(p: Page): GoogleWindow {
            runCatching { p.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(15_000.0)) }
            val first = p.url()
            if (first != "about:blank" && !guard(first)) {
                log.warn("closed a popup that opened at {}", first)
                runCatching { p.close() }
                return GoogleWindow.None
            }
            p.route("**/*") { route ->
                val req = route.request()
                if (req.isNavigationRequest && req.frame() == p.mainFrame() && !guard(req.url())) {
                    log.warn("blocked popup navigation to {}", req.url())
                    route.abort("blockedbyclient")
                } else {
                    route.resume()
                }
            }
            return GoogleWindow.Popup(PlaywrightPage(p, guard))
        }

        private fun fedCm(session: CDPSession, d: JsonObject): GoogleWindow = fedCmDialog(d) { dialogId, index ->
            session.send("FedCm.selectAccount", JsonObject().apply { addProperty("dialogId", dialogId); addProperty("accountIndex", index) })
            settle()
        }

        override fun settle() {
            runCatching { page.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(15_000.0)) }
            runCatching { page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(5_000.0)) }
        }

        override fun pause(millis: Long) { runCatching { page.waitForTimeout(millis.toDouble()) } }

        override fun screenshot(): ByteArray = page.screenshot(Page.ScreenshotOptions().setFullPage(true))
        override fun close() { runCatching { page.close() } }

        private fun el(id: String): com.microsoft.playwright.Locator {
            // Ids come from the snapshot script (e1, e2, …); anything else could break out of the selector.
            require(ID.matches(id)) { "invalid element id: $id" }
            return page.locator("[data-jobbot-id=\"$id\"]").first()
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        val ID = Regex("""^e\d{1,6}$""")
        /** How long a Google click is watched for a popup or FedCM dialog: 32 × 250 ms. */
        private const val POPUP_POLLS = 32
        private const val SNAPSHOT_TRIES = 3

        /**
         * Reads a CDP `FedCm.dialogShown` event. A payload of unexpected shape becomes a dialog
         * the fill hands off to Richard — never an exception.
         */
        internal fun fedCmDialog(d: JsonObject, select: (dialogId: String, index: Int) -> Unit): GoogleWindow.FedCm {
            fun str(o: JsonObject, key: String): String? = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            val dialogId = str(d, "dialogId") ?: return GoogleWindow.FedCm(UNREADABLE_DIALOG, emptyList()) {}
            // Positions are kept: the index selected is the dialog's own account index.
            val emails = d.get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.map { a -> a.takeIf { it.isJsonObject }?.asJsonObject?.let { str(it, "email") }.orEmpty() }.orEmpty()
            return GoogleWindow.FedCm(str(d, "dialogType").orEmpty(), emails) { index -> select(dialogId, index) }
        }

        const val UNREADABLE_DIALOG = "unreadable dialog"

        /**
         * Enumerates the page's interactive elements, tags each with a stable data-jobbot-id, and
         * reports labels, values (never a password's), options, and whether a control sits in a
         * form that already has user input. Also flags CAPTCHA widgets, and marks "Sign in with
         * Google" controls — including Google's own embedded button, which is a cross-origin iframe
         * (accounts.google.com/gsi/button) that a plain element query would never list.
         */
        val SNAPSHOT_JS = """
            () => {
              window.__jobbotSeq = window.__jobbotSeq || 0;
              const vis = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length) &&
                getComputedStyle(el).visibility !== 'hidden';
              const clean = s => (s || '').replace(/\s+/g, ' ').trim().slice(0, 200);
              const GOOGLE = /\b(sign|log)\s*(in|up|on)\b.*\bgoogle\b|\b(continue|register|join|apply)\s+(with|using|via)\s+google\b|^\s*google\s*$/i;
              const labelOf = el => {
                if (el.getAttribute('aria-label')) return el.getAttribute('aria-label');
                const by = el.getAttribute('aria-labelledby');
                if (by) { const t = by.split(' ').map(i => document.getElementById(i)).filter(Boolean).map(n => n.innerText).join(' '); if (t) return t; }
                if (el.labels && el.labels.length) return Array.from(el.labels).map(l => l.innerText).join(' ');
                const wrap = el.closest('label'); if (wrap) return wrap.innerText;
                if (el.placeholder) return el.placeholder;
                if (el.tagName === 'IFRAME') return el.title || '';
                const prev = el.closest('div,fieldset,li'); return prev ? prev.innerText.slice(0, 120) : '';
              };
              const filled = form => !!form && Array.from(form.querySelectorAll('input,textarea,select')).some(i =>
                !['hidden','submit','button','checkbox','radio','file'].includes((i.type||'').toLowerCase()) && i.value && i.value.trim());
              const bodyText = document.body ? document.body.innerText : '';
              const nodes = Array.from(document.querySelectorAll(
                'input,select,textarea,button,a[href],[role=button],[role=link],[role=checkbox],[role=radio],[role=combobox],[data-identifier],' +
                'iframe[src*="accounts.google.com/gsi/button"],iframe[id^="gsi_"]'));
              // No Google iframe: a plain clickable element reading "Sign in with Google" is the site's own button.
              const extras = new Set();
              if (/google/i.test(bodyText) && !nodes.some(n => n.tagName === 'IFRAME' && vis(n))) {
                for (const el of document.querySelectorAll('div,span,li,p')) {
                  const raw = el.textContent || '';
                  if (raw.length > 80 || !/google/i.test(raw) || !GOOGLE.test(clean(el.innerText)) || !vis(el)) continue;
                  if (getComputedStyle(el).cursor !== 'pointer' || nodes.some(n => n.contains(el) || el.contains(n))) continue;
                  nodes.push(el); extras.add(el);
                }
              }
              const out = [];
              for (const el of nodes) {
                const type = (el.getAttribute('type') || '').toLowerCase();
                if (type === 'hidden' || !vis(el) && type !== 'file') continue;
                if (!el.dataset.jobbotId) el.dataset.jobbotId = 'e' + (++window.__jobbotSeq);
                const tag = el.tagName.toLowerCase();
                const role = el.getAttribute('role');
                const iframe = tag === 'iframe';
                const form = el.form || el.closest('form');
                const kind = iframe || extras.has(el) ? 'button'
                  : tag === 'a' || role === 'link' || el.hasAttribute('data-identifier') ? 'link'
                  : tag === 'button' || role === 'button' || ['submit','button','reset'].includes(type) ? 'button'
                  : tag === 'select' ? 'select' : tag === 'textarea' ? 'textarea' : type === 'file' ? 'file'
                  : ['checkbox','radio'].includes(type) || ['checkbox','radio'].includes(role) ? type || role : 'input';
                // An input's value is user data (or a secret), never its label: only button-like inputs use it as text.
                const text = iframe ? 'Sign in with Google'
                  : clean(tag === 'input' ? (['submit','button','reset'].includes(type) ? el.value : '') : (el.innerText || ''));
                out.push({
                  id: el.dataset.jobbotId, kind, type: type || null, label: clean(labelOf(el)), name: el.name || null,
                  required: !!el.required || el.getAttribute('aria-required') === 'true',
                  value: type === 'password' ? (el.value ? '[set]' : '') : (kind === 'input' || kind === 'textarea' || kind === 'select') ? clean(el.value) : null,
                  options: tag === 'select' ? Array.from(el.options).map(o => clean(o.text)).slice(0, 60) : [],
                  checked: ['checkbox','radio'].includes(type) ? !!el.checked : null,
                  text,
                  href: tag === 'a' ? el.href : null,
                  inForm: !!form, formFilled: filled(form),
                  // Only a control's own words count: a field's fallback label is its container's text.
                  google: iframe || ((kind === 'button' || kind === 'link') && GOOGLE.test(text + ' ' + (el.getAttribute('aria-label') || ''))),
                });
                if (out.length >= 200) break;
              }
              const captcha = !!document.querySelector(
                'iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[src*="challenges.cloudflare"],iframe[src*="arkoselabs"],.g-recaptcha,.h-captcha,[data-sitekey],#px-captcha');
              return JSON.stringify({ url: location.href, title: document.title, elements: out, captcha,
                text: bodyText.slice(0, 4000) });
            }
        """.trimIndent()
    }
}
