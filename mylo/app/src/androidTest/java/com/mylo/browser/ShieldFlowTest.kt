package com.mylo.browser

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.mylo.browser.shield.ExitStatus
import com.mylo.browser.shield.MyloShield
import com.mylo.browser.shield.ServerDirectory
import com.mylo.browser.shield.ShieldEndpoint
import com.mylo.browser.shield.ShieldState
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mylo Shield on a real device, driven with UiAutomator so Android's own VPN dialog is part of the flow.
 *
 * [withoutAGatewayShieldIsUnavailable] runs on every build without a Shield service. [realTunnelMilestone]
 * runs only when a real test gateway is passed as instrumentation arguments (`-e shieldApiBaseUrl … -e
 * shieldDevToken …`, from CI secrets); it never runs against anything simulated. Screenshots and evidence
 * go to test-artifacts/shield/<case>/.
 */
@RunWith(AndroidJUnit4::class)
class ShieldFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val shield get() = MyloShield.get(context)

    private fun artifacts(case: String) = File(context.getExternalFilesDir(null), "test-artifacts/shield/$case").apply { deleteRecursively(); mkdirs() }

    @Test fun withoutAGatewayShieldIsUnavailable() {
        assumeTrue("A test gateway was supplied; this case covers builds without one", arguments.getString(URL_ARG).isNullOrBlank())
        shield.setTestGateway(null)
        assumeFalse("This build has its own Shield service", shield.configured)
        val dir = artifacts("unconfigured")
        val evidence = JSONObject().put("verified", false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val strip = waitFor(By.textContains("VPN protection"), "Home's VPN strip")
                assertFalse("Home claims no protection", device.hasObject(By.textContains("VPN protected")))
                shot(dir, "01-home.png")
                strip.click()
                waitFor(By.res("shield-screen"), "the Shield screen")
                assertEquals("VPN unavailable", waitFor(By.res("shield-status"), "status").text)
                assertEquals("Server setup required", device.findObject(By.res("shield-detail")).text)
                assertFalse("Connect is disabled without a gateway", device.findObject(By.res("shield-connect")).isEnabled)
                assertNotNull(device.findObject(By.res("shield-setup-required")))
                assertNull("No location is offered", device.findObject(By.res("shield-location")))
                assertNull("No connection details are shown", device.findObject(By.res("shield-details")))
                shot(dir, "02-shield-unavailable.png")
                device.pressBack()
                waitFor(By.textContains("VPN protection"), "Home after Back")
                evidence.put("verified", true).put("state", shield.engine.state.value.toString())
            }
        } finally {
            File(dir, "evidence.json").writeText(evidence.toString(2))
        }
    }

    @Test fun realTunnelMilestone() {
        val url = arguments.getString(URL_ARG)
        assumeTrue("Pass -e $URL_ARG (and -e $TOKEN_ARG) to run against a real test gateway", !url.isNullOrBlank())
        val dir = artifacts("milestone")
        val evidence = JSONObject().put("verified", false)
        val steps = JSONArray()
        try {
            // Start as a first-time user: no consent, no saved choice. Android's VPN permission is fresh on CI.
            context.getSharedPreferences("mylo_shield", 0).edit().clear().commit()
            assertTrue("The test gateway URL was refused", shield.setTestGateway(ShieldEndpoint(url!!, arguments.getString(TOKEN_ARG))))
            val directIp = publicIp()
            evidence.put("publicIpBeforeConnecting", directIp)
            assertNull("No VPN may be active before connecting", vpnNetwork())

            ActivityScenario.launch(MainActivity::class.java).use {
                // 1. Disconnected Shield screen, with the gateway list from the real service.
                waitFor(By.textContains("VPN protection"), "Home's VPN strip").click()
                waitFor(By.res("shield-screen"), "the Shield screen")
                val servers = awaitServers()
                evidence.put("serversListed", JSONArray(servers.map { "${it.id} · ${it.label}" }))
                assertEquals("Not connected", waitFor(By.res("shield-status"), "status").text)
                shot(dir, "01-disconnected.png"); steps.put("disconnected")

                // 2. Mylo's disclosure, then Android's VPN permission dialog.
                device.findObject(By.res("shield-connect")).click()
                waitFor(By.res("shield-disclosure"), "the Shield disclosure")
                shot(dir, "02-disclosure.png"); steps.put("disclosure")
                device.findObject(By.res("shield-disclosure-agree")).click()
                waitFor(By.pkg("com.android.vpndialogs"), "Android's VPN permission dialog")
                shot(dir, "03-android-vpn-permission.png"); steps.put("android-vpn-permission")
                (device.findObject(By.res("android:id/button1")) ?: waitFor(By.text("OK"), "the dialog's OK button")).click()
                device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 4_000)?.click()

                // 3. Connecting, 4. connected only after a real handshake and exit check.
                if (device.wait(Until.hasObject(By.res("shield-status").text("Connecting…")), 5_000)) { shot(dir, "04-connecting.png"); steps.put("connecting") }
                awaitStatus("Connected", 90_000)
                shot(dir, "05-connected.png"); steps.put("connected")
                val connected = shield.engine.state.value as ShieldState.Connected
                val exit = connected.exit as? ExitStatus.Verified ?: throw AssertionError("Exit not verified: ${connected.exit}")

                // 7. Evidence that this device's traffic uses the tunnel.
                val tunnel = vpnNetwork() ?: throw AssertionError("Android reports no VPN network while connected")
                val tunnelIp = publicIp()
                assertEquals("The public IP seen through the tunnel is the gateway's exit", exit.ip, tunnelIp)
                assertNotEquals("The public IP changed when Shield connected", directIp, tunnelIp)
                evidence.put("server", "${connected.server.id} · ${connected.server.label} · ${connected.server.hostname}")
                    .put("verifiedExitIp", exit.ip).put("publicIpThroughTunnel", tunnelIp)
                    .put("androidVpnNetwork", true)
                    .put("tunnelDnsServers", JSONArray(connectivity().getLinkProperties(tunnel)?.dnsServers?.map { it.hostAddress }.orEmpty()))
                    .put("ipv6ThroughTunnel", connected.ipv6)

                // 5. The location selector lists the service's real gateways.
                device.findObject(By.res("shield-location")).click()
                waitFor(By.res("shield-servers"), "the location list")
                shot(dir, "06-locations.png"); steps.put("locations")
                device.pressBack()
                device.wait(Until.gone(By.res("shield-servers")), 5_000)

                // 6. Disconnect: the public IP returns to the device's own, then reconnect.
                device.findObject(By.res("shield-connect")).click()
                awaitStatus("Not connected", 30_000)
                awaitNoVpn()
                shot(dir, "07-disconnected-again.png"); steps.put("disconnected-again")
                val afterIp = publicIp()
                assertEquals("Disconnecting restores the device's own public IP", directIp, afterIp)
                evidence.put("publicIpAfterDisconnect", afterIp)

                device.findObject(By.res("shield-connect")).click()
                awaitStatus("Connected", 90_000)
                shot(dir, "08-reconnected.png"); steps.put("reconnected")
                assertEquals("Reconnected traffic leaves through the gateway again", exit.ip, publicIp())
                device.findObject(By.res("shield-connect")).click()
                awaitStatus("Not connected", 30_000)
                evidence.put("verified", true)
            }
        } finally {
            if (shield.engine.state.value !is ShieldState.Disconnected) { shield.disconnect(); SystemClock.sleep(2_000) }
            shield.setTestGateway(null)
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).toString(2))
        }
    }

    private fun awaitServers() = run {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            (shield.engine.directory.value as? ServerDirectory.Loaded)?.servers?.takeIf { it.isNotEmpty() }?.let { return@run it }
            SystemClock.sleep(250)
        }
        throw AssertionError("The Shield service listed no servers: ${shield.engine.directory.value}")
    }

    private fun awaitStatus(text: String, timeout: Long) {
        if (!device.wait(Until.hasObject(By.res("shield-status").text(text)), timeout)) {
            throw AssertionError("Shield did not reach \"$text\"; state=${shield.engine.state.value}")
        }
    }

    private fun waitFor(selector: androidx.test.uiautomator.BySelector, what: String): UiObject2 =
        device.wait(Until.findObject(selector), 15_000) ?: throw AssertionError("$what did not appear")

    private fun shot(dir: File, name: String) {
        SystemClock.sleep(600)
        assertTrue("Could not save screenshot $name", device.takeScreenshot(File(dir, name)))
    }

    private fun connectivity() = context.getSystemService(ConnectivityManager::class.java)

    private fun vpnNetwork(): Network? = connectivity().allNetworks.firstOrNull {
        connectivity().getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun awaitNoVpn() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (vpnNetwork() != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        assertNull("Android still reports a VPN after disconnecting", vpnNetwork())
    }

    /** This app's public IPv4 address as an independent echo service sees it (test only). */
    private fun publicIp(): String {
        var last: Exception? = null
        repeat(3) {
            try {
                val connection = URL(IP_ECHO).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                return connection.inputStream.bufferedReader().use { it.readText().trim() }.also { connection.disconnect() }
            } catch (e: Exception) { last = e; SystemClock.sleep(1_000) }
        }
        throw AssertionError("Could not read the public IP", last)
    }

    companion object {
        const val URL_ARG = "shieldApiBaseUrl"
        const val TOKEN_ARG = "shieldDevToken"
        private const val IP_ECHO = "https://api.ipify.org"
    }
}
