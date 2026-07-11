package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.gmail.GmailAuth
import com.jd.pipeline.config.Config
import java.nio.file.Files
import java.nio.file.Path

object ReauthCommandHandler {
    fun run() {
        println("[INFO] Initiating Gmail OAuth re-authentication...")
        try {
            val tokenPath = Path.of(Config.GMAIL_TOKEN_FILE)
            if (Files.deleteIfExists(tokenPath)) {
                println("[INFO] Deleted existing token at: ${Config.GMAIL_TOKEN_FILE}")
            }
            GmailAuth.generateToken()
            println("[OK] Re-authentication complete. Run --check-token to verify.")
        } catch (e: Exception) {
            throw RuntimeException("Re-authentication failed: ${e.message}", e)
        }
    }
}
