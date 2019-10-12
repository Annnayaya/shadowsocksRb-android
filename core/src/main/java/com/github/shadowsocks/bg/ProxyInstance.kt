/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.bg

import android.content.Context
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.acl.AclSyncer
import com.github.shadowsocks.core.BuildConfig
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.net.HostsFile
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.parseNumericAddress
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

/**
 * This class sets up environment for ss-local.
 */
class ProxyInstance(val profile: Profile, private val route: String = profile.route) {
    private var configFile: File? = null
    var trafficMonitor: TrafficMonitor? = null
    private val host = profile.host

    suspend fun init(service: BaseService.Interface, hosts: HostsFile) {

        // it's hard to resolve DNS on a specific interface so we'll do it here
        if (profile.host.parseNumericAddress() == null) {
            profile.host = (hosts.resolve(profile.host).firstOrNull() ?: try {
                service.resolver(profile.host).firstOrNull()
            } catch (_: IOException) {
                null
            })?.hostAddress ?: throw UnknownHostException()
        }
    }

    /**
     * Sensitive shadowsocks configuration file requires extra protection. It may be stored in encrypted storage or
     * device storage, depending on which is currently available.
     */
    fun start(service: BaseService.Interface, stat: File, configFile: File, extraFlag: String? = null) {
        trafficMonitor = TrafficMonitor(stat)

        this.configFile = configFile
        val config = profile.toJson()
        configFile.writeText(config.toString())

        val cmd = service.buildAdditionalArguments(arrayListOf(
                File((service as Context).applicationInfo.nativeLibraryDir, Executable.SS_LOCAL).absolutePath,
                "-b", DataStore.listenAddress,
                "-l", DataStore.portProxy.toString(),
                "-t", "600",
                "--host", host,
                "-S", stat.absolutePath,
                "-c", configFile.absolutePath))
        if (extraFlag != null) cmd.add(extraFlag)

        if (route != Acl.ALL) {
            cmd += "--acl"
            cmd += Acl.getFile(route).absolutePath
        }

        if (DataStore.tcpFastOpen) cmd += "--fast-open"
        if (BuildConfig.DEBUG) cmd += "-v"

        service.data.processes!!.start(cmd)
    }

    fun scheduleUpdate() {
        if (route !in arrayOf(Acl.ALL, Acl.CUSTOM_RULES)) AclSyncer.schedule(route)
    }

    fun shutdown(scope: CoroutineScope) {
        trafficMonitor?.apply {
            thread.shutdown(scope)
            persistStats(profile.id)    // Make sure update total traffic when stopping the runner
        }
        trafficMonitor = null
        configFile?.delete()    // remove old config possibly in device storage
        configFile = null
    }
}
