package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.client.gmail.GmailAuth

object TokenFromUrlCommandHandler {
    fun run(command: Command.TokenFromUrl) {
        println("[INFO] Exchanging redirect URL for Gmail token...")
        val success = GmailAuth.exchangeRedirectUrlForToken(command.redirectUrl)
        if (success) {
            println("[INFO] Token saved successfully.")
        } else {
            throw RuntimeException("Failed to exchange redirect URL for token.")
        }
    }
}
