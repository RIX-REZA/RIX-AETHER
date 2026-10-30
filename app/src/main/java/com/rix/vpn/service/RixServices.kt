package com.rix.vpn.service

import android.content.Context
import com.rix.vpn.core.PsiphonBridge
import com.rix.vpn.core.RixRuntime
import com.rix.vpn.data.LogBus

/**
 * Stops whichever session is live, without having to know which one it is.
 *
 * The two modes are two different Android services that publish into one
 * [RixRuntime]. Addressing the stop by the mode the settings screen happens to
 * show is therefore unsafe: switch the segmented control to «فقط پروکسی» while
 * the tunnel is up and the next press of the orb stops the idle proxy service
 * instead of the live tunnel. The screen then says «آماده به اتصال» while the
 * VpnService still holds its TUN — which is the system's VPN key icon and the
 * ongoing notification, both of which then outlive the connection with no way
 * left in the UI to clear them.
 *
 * So: always both. A stop aimed at a service that is not running costs one
 * start command and nothing else, and it is what makes this idempotent.
 */
object RixServices {

    fun stopAll(context: Context) {
        RixVpnService.stop(context)
        RixProxyService.stop(context)
    }

    /**
     * Destroys both services outright.
     *
     * This skips the graceful teardown, so it is only for the cases where the
     * graceful one has already failed: a start the system refused, or a
     * teardown that never reached its end. A VpnService that is destroyed
     * releases its TUN, which is the part that actually clears the key icon;
     * `onDestroy` in both services takes the engine and the core down with it.
     */
    fun forceStopAll(context: Context) {
        LogBus.e("vpn", "forcing the services down")
        // Psiphon is not an Android service, so destroying the two above does
        // not reach it: without this it stays up on the port the stopped
        // session left behind, and the next connect finds it already in use.
        PsiphonBridge.stopAsync()
        RixVpnService.forceStop(context)
        RixProxyService.forceStop(context)
        // Nothing is left running to publish a final phase, and leaving the UI
        // in STOPPING would make the orb inert for good.
        //
        // markIdle() and not idle(): the engine may still be on its way out, and
        // idle() also clears the hev bookkeeping that stopTun2Socks() needs to
        // signal it. Clearing that first would make the shutdown look like it
        // never started an engine and leave the thread running.
        RixRuntime.markIdle()
    }
}
