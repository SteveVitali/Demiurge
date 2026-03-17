package demiurge.orchestrator

import java.nio.file.{Files, Path}

import io.circe.Json
import io.circe.syntax._
import demiurge.model._
import demiurge.worker.{WorkerProcessManager, WorkerMessages}

// Gap 4: AuthBootstrapExecutor — executes auth bootstrap between SeedingFixtures
// and ReadyToVerify. For browser-based modes, delegates to the worker process.
// For local modes (StaticTestToken, DevBypassHeader, SeededLocalSession), handles
// locally without worker involvement. Auth failure is non-fatal.
object AuthBootstrapExecutor {

  case class AuthResult(
    success:          Boolean,
    storageStatePath: Option[String],
    apiHeaders:       Map[String, String] = Map.empty,
    errorMessage:     Option[String] = None,
  )

  def execute(
    authConfig:     ResolvedAuthConfig,
    workerManager:  Option[WorkerProcessManager],
    worktreePath:   Path,
    runId:          String,
  ): AuthResult = {
    try {
      authConfig.mode match {
        case AuthMode.StaticTestToken =>
          executeStaticTestToken(authConfig, worktreePath, runId)

        case AuthMode.DevBypassHeader =>
          executeDevBypassHeader(authConfig, worktreePath, runId)

        case AuthMode.SeededLocalSession =>
          executeSeededLocalSession(authConfig, worktreePath, runId)

        case AuthMode.BrowserFormLogin | AuthMode.ApiLogin =>
          workerManager match {
            case Some(wm) =>
              executeViaWorker(authConfig, wm, worktreePath, runId)
            case None =>
              AuthResult(
                success = false,
                storageStatePath = None,
                errorMessage = Some(s"Auth mode ${authConfig.mode} requires worker, but no WorkerProcessManager available"),
              )
          }
      }
    } catch {
      case e: Exception =>
        AuthResult(
          success = false,
          storageStatePath = None,
          errorMessage = Some(s"Auth bootstrap exception: ${e.getMessage}"),
        )
    }
  }

  private def executeStaticTestToken(
    authConfig:   ResolvedAuthConfig,
    worktreePath: Path,
    runId:        String,
  ): AuthResult = {
    val token = authConfig.staticToken.getOrElse("test-token")
    val storageStatePath = writeStorageState(
      worktreePath,
      Json.obj(
        "cookies" -> Json.arr(),
        "origins" -> Json.arr(),
        "headers" -> Json.obj("Authorization" -> s"Bearer $token".asJson),
      ),
    )
    AuthResult(
      success = true,
      storageStatePath = Some(storageStatePath),
      apiHeaders = Map("Authorization" -> s"Bearer $token"),
    )
  }

  private def executeDevBypassHeader(
    authConfig:   ResolvedAuthConfig,
    worktreePath: Path,
    runId:        String,
  ): AuthResult = {
    val headers = authConfig.credentials
    val storageStatePath = writeStorageState(
      worktreePath,
      Json.obj(
        "cookies" -> Json.arr(),
        "origins" -> Json.arr(),
        "headers" -> headers.asJson,
      ),
    )
    AuthResult(
      success = true,
      storageStatePath = Some(storageStatePath),
      apiHeaders = headers,
    )
  }

  private def executeSeededLocalSession(
    authConfig:   ResolvedAuthConfig,
    worktreePath: Path,
    runId:        String,
  ): AuthResult = {
    val storageStatePath = writeStorageState(
      worktreePath,
      Json.obj(
        "cookies" -> Json.arr(),
        "origins" -> Json.arr(),
      ),
    )
    AuthResult(
      success = true,
      storageStatePath = Some(storageStatePath),
    )
  }

  private def executeViaWorker(
    authConfig:    ResolvedAuthConfig,
    workerManager: WorkerProcessManager,
    worktreePath:  Path,
    runId:         String,
  ): AuthResult = {
    val storageOutput = authConfig.storageStateOutput.getOrElse(
      storageStateDir(worktreePath).resolve("storage-state.json").toString)

    val modeStr = authConfig.mode match {
      case AuthMode.BrowserFormLogin => "browser_form_login"
      case AuthMode.ApiLogin         => "api_login"
      case other                     => other.toString.toLowerCase
    }

    val params = WorkerMessages.executeAuthBootstrapParams(
      taskId = s"auth-$runId",
      mode = modeStr,
      loginUrl = authConfig.loginUrl,
      credentials = authConfig.credentials,
      storageStateOutput = storageOutput,
    )

    workerManager.executeAuthBootstrap(params, timeoutMs = 60000) match {
      case Right(result) =>
        AuthResult(
          success = result.success,
          storageStatePath = result.storageStatePath,
          apiHeaders = result.apiHeaders,
          errorMessage = result.errorMessage,
        )
      case Left(err) =>
        AuthResult(
          success = false,
          storageStatePath = None,
          errorMessage = Some(s"Worker auth bootstrap failed: $err"),
        )
    }
  }

  private def storageStateDir(worktreePath: Path): Path = {
    val dir = worktreePath.resolve(".demiurge").resolve("auth")
    Files.createDirectories(dir)
    dir
  }

  private def writeStorageState(worktreePath: Path, json: Json): String = {
    val dir = storageStateDir(worktreePath)
    val file = dir.resolve("storage-state.json")
    Files.write(file, json.noSpaces.getBytes("UTF-8"))
    file.toString
  }
}
