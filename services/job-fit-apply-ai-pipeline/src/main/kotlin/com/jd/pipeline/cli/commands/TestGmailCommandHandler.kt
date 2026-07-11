package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.state.emailIntake

object TestGmailCommandHandler {
    fun run() {
        println("[INFO] Fetching inbox emails...")

        try {
            val client = GmailTransport()
            val emails = client.fetchJdEmails(10, false)

            if (emails.isEmpty()) {
                println("[WARN] No emails returned - check credentials or search query.")
                return
            }

            println("\n┌─────┬────────────────┬────────────────────────────────────────┐")
            println("│  #  │ ID            │ Subject                              │")
            println("├─────┼────────────────┼────────────────────────────────────────┤")

            emails.forEachIndexed { i, email ->
                val id = email.emailIntake?.emailId ?: ""
                var subject = email.emailIntake?.subject ?: ""
                if (subject.length > 40) {
                    subject = subject.take(37) + "..."
                }
                println("│ %-3d │ %-14s │ %-38s │".format(i + 1, id.take(14), subject))
            }

            println("└─────┴────────────────┴────────────────────────────────────────┘")
            println("\n[INFO] Fetched ${emails.size} email(s).")

        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }
}
