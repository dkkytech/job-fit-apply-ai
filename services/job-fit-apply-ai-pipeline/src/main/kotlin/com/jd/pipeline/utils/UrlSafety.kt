package com.jd.pipeline.utils

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Keeps the scraper from fetching private-network targets. Job links come from places we don't
 * control — anyone can email a "job alert", and POST /api/urls takes a link — so without this the
 * processor could be pointed at localhost, the Docker host (host.docker.internal), a cloud
 * metadata endpoint or a tailnet device.
 *
 * A target is private when its host is a known internal name, or when it resolves to a loopback,
 * link-local, site-local (RFC 1918), CGNAT/tailnet (100.64.0.0/10), unique-local IPv6, wildcard or
 * multicast address. A name that doesn't resolve is NOT private: the fetch then fails on its own.
 */
object UrlSafety {
    private val INTERNAL_NAMES = setOf("localhost", "host.docker.internal", "gateway.docker.internal", "metadata.google.internal", "metadata")
    private val INTERNAL_SUFFIXES = listOf(".localhost", ".internal", ".local", ".lan", ".home.arpa")

    fun isPrivateTarget(url: String, resolve: (String) -> List<InetAddress> = ::resolveAll): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trim('[', ']')?.trimEnd('.') ?: return false
        if (host.isEmpty()) return false
        if (host in INTERNAL_NAMES || INTERNAL_SUFFIXES.any { host.endsWith(it) }) return true
        return resolve(host).any(::isPrivateAddress)
    }

    internal fun isPrivateAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (a) {
            // 100.64.0.0/10: carrier-grade NAT — Tailscale hands out tailnet addresses from it.
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            // fc00::/7: unique local addresses (incl. Tailscale's fd7a:115c:a1e0::/48).
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    private fun resolveAll(host: String): List<InetAddress> =
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
}
