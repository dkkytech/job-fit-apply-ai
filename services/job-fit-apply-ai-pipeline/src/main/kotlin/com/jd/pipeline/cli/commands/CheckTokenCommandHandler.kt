package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.gmail.GmailAuth

object CheckTokenCommandHandler {
    fun run() {
        println("[INFO] Checking Gmail token status...")

        try {
            val result = GmailAuth.checkTokenStatus()

            println(result.message)

            if (result.emailAddress != null) {
                println("[INFO] Authenticated as: ${result.emailAddress}")
            }

            when (result.status) {
                GmailAuth.TokenStatus.VALID -> {
                    println("[INFO] Token status: VALID")
                    return
                }
                GmailAuth.TokenStatus.MISSING -> {
                    throw IllegalStateException("Token status: MISSING — run with --reauth to obtain a new token")
                }
                GmailAuth.TokenStatus.EXPIRED -> {
                    throw IllegalStateException("Token status: EXPIRED — run with --reauth to obtain a new token")
                }
                GmailAuth.TokenStatus.INVALID -> {
                    throw IllegalStateException("Token status: INVALID — run with --reauth to obtain a new token")
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Token check failed: ${e.message}", e)
        }
    }
}
