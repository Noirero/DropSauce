package org.koitharu.kotatsu.core.network

import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.net.InetAddress
import java.net.UnknownHostException

class DoHManager(
	cache: Cache,
	private val settings: AppSettings,
	context: Context,
) : Dns {

	private val bootstrapClient = OkHttpClient.Builder().cache(cache).build()
	private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	private var cachedDelegate: Dns? = null
	private var cachedProvider: DoHProvider? = null
	private var cachedCustomUrl: String? = null

	override fun lookup(hostname: String): List<InetAddress> {
		return try {
			getDelegate().lookup(hostname)
		} catch (e: UnknownHostException) {
			// Keep the existing safety fallback: if the selected DoH resolver is unreachable,
			// normal system DNS still allows the request to proceed.
			Dns.SYSTEM.lookup(hostname)
		}
	}

	@Synchronized
	private fun getDelegate(): Dns {
		var delegate = cachedDelegate
		val provider = settings.dnsOverHttps
		val customUrl = if (provider == DoHProvider.CUSTOM) {
			prefs.getString(KEY_CUSTOM_URL, null)?.trim()?.takeIf { it.isNotEmpty() }
		} else {
			null
		}
		if (delegate == null || provider != cachedProvider || customUrl != cachedCustomUrl) {
			delegate = createDelegate(provider, customUrl)
			cachedDelegate = delegate
			cachedProvider = provider
			cachedCustomUrl = customUrl
		}
		return delegate
	}

	private fun createDelegate(provider: DoHProvider, customUrl: String?): Dns = when (provider) {
		DoHProvider.NONE -> Dns.SYSTEM
		DoHProvider.GOOGLE -> doh(
			url = "https://dns.google/dns-query",
			"8.8.4.4",
			"8.8.8.8",
			"2001:4860:4860::8888",
			"2001:4860:4860::8844",
		)

		DoHProvider.CLOUDFLARE -> doh(
			url = "https://cloudflare-dns.com/dns-query",
			"162.159.36.1",
			"162.159.46.1",
			"1.1.1.1",
			"1.0.0.1",
			"162.159.132.53",
			"2606:4700:4700::1111",
			"2606:4700:4700::1001",
			"2606:4700:4700::0064",
			"2606:4700:4700::6400",
		)

		DoHProvider.ADGUARD -> doh(
			url = "https://dns-unfiltered.adguard.com/dns-query",
			"94.140.14.140",
			"94.140.14.141",
			"2a10:50c0::1:ff",
			"2a10:50c0::2:ff",
		)

		DoHProvider.QUAD9 -> doh(
			url = "https://dns.quad9.net/dns-query",
			"9.9.9.9",
			"149.112.112.112",
			"2620:fe::fe",
			"2620:fe::9",
		)

		DoHProvider.ALIDNS -> doh(
			url = "https://dns.alidns.com/dns-query",
			"223.5.5.5",
			"223.6.6.6",
			"2400:3200::1",
			"2400:3200:baba::1",
		)

		DoHProvider.DNSPOD -> doh(
			url = "https://doh.pub/dns-query",
			"1.12.12.12",
			"120.53.53.53",
		)

		DoHProvider.THREE_SIXTY -> doh(
			url = "https://doh.360.cn/dns-query",
			"101.226.4.6",
			"218.30.118.6",
			"123.125.81.6",
			"140.207.198.6",
			"180.163.249.75",
			"101.199.113.208",
			"36.99.170.86",
		)

		DoHProvider.QUAD101 -> doh(
			url = "https://dns.twnic.tw/dns-query",
			"101.101.101.101",
			"2001:de4::101",
			"2001:de4::102",
		)

		DoHProvider.MULLVAD -> doh(
			url = "https://dns.mullvad.net/dns-query",
			"194.242.2.2",
			"2a07:e340::2",
		)

		DoHProvider.CONTROLD -> doh(
			url = "https://freedns.controld.com/p0",
			"76.76.2.0",
			"76.76.10.0",
			"2606:1a40::",
			"2606:1a40:1::",
		)

		DoHProvider.NJALLA -> doh(
			url = "https://dns.njal.la/dns-query",
			"95.215.19.53",
			"2001:67c:2354:2::53",
		)

		DoHProvider.SHECAN -> doh(
			url = "https://free.shecan.ir/dns-query",
			"178.22.122.100",
			"185.51.200.2",
		)

		// Bootstrap via its Cloudflare anycast IPs — without them this provider only worked when
		// the system DNS could already resolve v.recipes.
		DoHProvider.ZERO_MS -> doh(
			url = "https://v.recipes/dns-query",
			"104.26.0.241",
			"104.26.1.241",
			"172.67.69.243",
		)

		DoHProvider.CUSTOM -> customUrl
			?.toHttpUrlOrNull()
			?.takeIf { it.scheme == "https" }
			?.let { doh(it) }
			?: Dns.SYSTEM
	}

	private fun doh(
		url: String,
		vararg bootstrapHosts: String,
	): Dns = doh(url.toHttpUrl(), *bootstrapHosts)

	private fun doh(
		url: HttpUrl,
		vararg bootstrapHosts: String,
	): Dns {
		val builder = DnsOverHttps.Builder()
			.client(bootstrapClient)
			.url(url)
			.resolvePrivateAddresses(true)
		val bootstrap = bootstrapHosts.mapNotNull(::tryGetByIp)
		// Custom endpoints generally do not have known bootstrap IPs. In that case leave bootstrap
		// unset so the dedicated bootstrap client resolves the DoH endpoint using system DNS once;
		// all subsequent target hostname lookups go through the configured HTTPS resolver.
		if (bootstrap.isNotEmpty()) {
			builder.bootstrapDnsHosts(bootstrap)
		}
		return builder.build()
	}

	private fun tryGetByIp(ip: String): InetAddress? = try {
		InetAddress.getByName(ip)
	} catch (e: UnknownHostException) {
		e.printStackTraceDebug()
		null
	}

	companion object {
		const val KEY_CUSTOM_URL = "dns_over_https_custom_url"
	}
}
