package com.metrolist.music.playback

import com.metrolist.music.utils.StatsLog
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * The server group inside a googlevideo host name, or null when there is none to read.
 *
 * Hosts look like `rr1---sn-ajixh5-55.googlevideo.com`: `rr1` is one replica among several serving
 * the same content, and `sn-ajixh5-55` names the group. A signed link is issued against the group,
 * so the group is what a refusal is worth attributing to.
 */
internal fun googlevideoServerGroup(host: String?): String? {
    if (host == null) return null
    val lower = host.lowercase()
    if (!lower.endsWith(".googlevideo.com")) return null
    return lower
        .substringBefore('.')
        .split("---")
        .firstOrNull { it.startsWith("sn-") && it.length > "sn-".length }
}

/**
 * Records what googlevideo answers, so this build's refusals can be counted against another's.
 *
 * Deliberately passive. It reads the status line and the timing and nothing else — no URL, no
 * query string, no signature, no PoToken, no cookie — and it never alters a request, a response or
 * a retry. The point of the fork is to measure this app unchanged; an interceptor that changed
 * behaviour would measure something that does not exist.
 *
 * One line per network hop, which is what makes a redirect visible as two.
 */
class CdnWireInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!StatsLog.isEnabled || googlevideoServerGroup(host) == null) return chain.proceed(request)

        val startedAtNs = System.nanoTime()
        return try {
            chain.proceed(request).also { response ->
                val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000
                val line = "cdn-wire host=%s group=%s status=%d elapsedMs=%d"
                if (response.code >= 400) {
                    Timber.tag("StatsCDN").w(
                        line,
                        host,
                        googlevideoServerGroup(host) ?: "unknown",
                        response.code,
                        elapsedMs,
                    )
                } else {
                    Timber.tag("StatsCDN").d(
                        line,
                        host,
                        googlevideoServerGroup(host) ?: "unknown",
                        response.code,
                        elapsedMs,
                    )
                }
            }
        } catch (failure: IOException) {
            Timber.tag("StatsCDN").w(
                "cdn-wire-iofail host=%s group=%s elapsedMs=%d type=%s",
                host,
                googlevideoServerGroup(host) ?: "unknown",
                (System.nanoTime() - startedAtNs) / 1_000_000,
                failure::class.java.simpleName,
            )
            throw failure
        }
    }
}
