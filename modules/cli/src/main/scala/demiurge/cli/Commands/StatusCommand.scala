package demiurge.cli.Commands

import java.sql.Connection

import demiurge.cli._
import demiurge.cli.CommandParsers._
import demiurge.model._
import demiurge.persistence._
import demiurge.license.{LicenseManager, LicenseStatus, CredentialStore}

// Phase 7: `demiurge status` command — Spec §14.1
// Spec 05 §6.4: Enhanced with license, usage, and API key info
object StatusCommand {

  def execute(cmd: StatusCmd, global: GlobalOpts, conn: Connection): Int = {
    implicit val c: Connection = conn

    cmd.runId match {
      case Some(id) =>
        TaskRunRepo.getById(id) match {
          case None =>
            System.err.println(OutputFormatter.formatError(s"Run not found: $id", global.format))
            ExitCodes.NotFound
          case Some(run) =>
            System.out.println(OutputFormatter.formatRun(run, global.format))
            ExitCodes.Success
        }
      case None =>
        // Spec 05 §6.4: Show full status dashboard
        val sb = new StringBuilder
        sb.append("Demiurge\n")
        sb.append("\u2550" * 30 + "\n\n")

        // Account section
        sb.append("Account\n")
        LicenseManager.validate() match {
          case LicenseStatus.Valid(planTier, uses, maxUses, expiry, _) =>
            val creds = CredentialStore.loadCredentials()
            val email = creds.flatMap(_.userEmail).getOrElse("(unknown)")
            val planLabel = if (planTier.nonEmpty) planTier.capitalize else "Active"
            sb.append(s"  Email:     $email\n")
            sb.append(s"  Plan:      $planLabel\n")
            if (expiry.nonEmpty) sb.append(s"  Expires:   $expiry\n")
            sb.append("\n")

            // Usage section
            sb.append("Usage (this period)\n")
            val runPct = if (maxUses > 0) (uses.toDouble / maxUses * 100).toInt else 0
            sb.append(s"  Runs:      $uses / $maxUses  ${progressBar(runPct)} $runPct%\n")
            sb.append("\n")

          case LicenseStatus.NoCredentials =>
            sb.append("  Not logged in. Run `demiurge login` to authenticate.\n\n")
          case LicenseStatus.Expired(expiry) =>
            sb.append(s"  License expired on $expiry. Renew at https://demiurge.dev/billing\n\n")
          case LicenseStatus.NetworkError(msg) =>
            sb.append(s"  Cannot validate license: $msg\n\n")
          case other =>
            sb.append(s"  License status: $other\n\n")
        }

        // API Keys section
        sb.append("API Keys\n")
        val anthropicKey = CredentialStore.resolveApiKey("ANTHROPIC_API_KEY", "anthropic")
        anthropicKey match {
          case Some(k) if k.length > 8 =>
            sb.append(s"  Anthropic: ${k.take(5)}...${k.takeRight(4)} \u2713\n")
          case Some(_) =>
            sb.append("  Anthropic: (configured) \u2713\n")
          case None =>
            sb.append("  Anthropic: (not set)\n")
        }
        val openaiKey = CredentialStore.resolveApiKey("OPENAI_API_KEY", "openai")
        openaiKey match {
          case Some(k) if k.length > 8 =>
            sb.append(s"  OpenAI:    ${k.take(5)}...${k.takeRight(4)} \u2713\n")
          case Some(_) =>
            sb.append("  OpenAI:    (configured) \u2713\n")
          case None =>
            sb.append("  OpenAI:    (not set)\n")
        }
        sb.append("\n")

        // Recent Runs section
        val runs = TaskRunRepo.listRecent(10)
        if (runs.nonEmpty) {
          sb.append("Recent Runs\n")
          runs.foreach { run =>
            val statusIcon = run.status match {
              case RunStatus.Succeeded => "\u2713"
              case RunStatus.Exhausted => "\u2717"
              case RunStatus.Cancelled | RunStatus.Interrupted => "\u2022"
              case _ => "\u25cb"
            }
            val task = run.taskText.take(50)
            sb.append(s"  $statusIcon ${run.runId.take(8)}  ${run.status}  $task\n")
          }
        }

        System.out.println(sb.toString())
        ExitCodes.Success
    }
  }

  /** Render a Unicode progress bar (20 chars wide). */
  private def progressBar(pct: Int): String = {
    val filled = (pct.min(100).max(0) / 5) // 0-20
    val empty = 20 - filled
    "\u2588" * filled + "\u2591" * empty
  }
}
