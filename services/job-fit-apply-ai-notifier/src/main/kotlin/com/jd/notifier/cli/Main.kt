package com.jd.notifier.cli

import com.jd.notifier.NotifierLoop
import com.jd.notifier.bridge.NotifierBridgeClient
import com.jd.notifier.config.Config
import com.jd.notifier.health.Heartbeat
import com.jd.notifier.notify.Notifier
import com.jd.notifier.notify.NotificationClient
import com.jd.notifier.state.Cursor
import java.nio.file.Path

/**
 * Notifier entrypoint — a completed-feed event consumer. Long-running (`--poll`) since it's
 * near-real-time. Sends Discord (per job) + Telegram (high-fit); tracks its own cursor.
 *   --poll     run the notify loop (the long-running service)
 *   --once     drain the stream once (tests / manual)
 *   --health   exit 0 if the loop is alive (container healthcheck)
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        when (parse(args)) {
            Command.Poll -> {
                val nc = NotificationClient()
                println("[notifier] starting — bridge=${Config.JD_BRIDGE_URL}, " +
                    "discord=${nc.discordConfigured}, telegram=${nc.telegramConfigured}, threshold=${Config.FIT_THRESHOLD}")
                if (!nc.discordConfigured && !nc.telegramConfigured)
                    System.err.println("[notifier] WARNING: no Discord/Telegram credentials — nothing will be sent")
                loop(nc).runForever()
            }
            Command.Once -> loop(NotificationClient()).drainOnce()
            Command.Health -> {
                val hb = Heartbeat.fromConfig(Config.HEARTBEAT_FILE)
                if (hb.isFresh(Config.HEALTH_MAX_AGE_MS, System.currentTimeMillis())) println("[health] ok")
                else {
                    System.err.println("[health] stale/missing heartbeat at ${Config.HEARTBEAT_FILE}")
                    kotlin.system.exitProcess(1)
                }
            }
            Command.Usage -> printUsage()
        }
    }

    private fun loop(nc: NotificationClient) = NotifierLoop(
        bridge = NotifierBridgeClient(),
        notifier = Notifier(nc),
        cursor = Cursor(Path.of(Config.CURSOR_FILE)),
        heartbeat = Heartbeat.fromConfig(Config.HEARTBEAT_FILE),
    )

    internal fun parse(args: Array<String>): Command = when {
        args.contains("--health") -> Command.Health
        args.contains("--once") -> Command.Once
        args.contains("--poll") -> Command.Poll
        else -> Command.Usage
    }

    private fun printUsage() = println(
        """
        Usage: notifier <command>
          --poll     Run the notify loop (long-running service)
          --once     Drain the completed-event stream once
          --health   Exit 0 if the loop is alive (container healthcheck)
        """.trimIndent()
    )
}

enum class Command { Poll, Once, Health, Usage }
