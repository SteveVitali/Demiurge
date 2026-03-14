package demiurge.artifact

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPOutputStream

import demiurge.model.{ArtifactRecord, ArtifactType, ArtifactTypeCodec}

// Spec §12.2: ArtifactSinkImpl — real artifact sink with temp-file-then-rename,
// SHA-256 checksums, gzip compression, disk budget enforcement.
class ArtifactSinkImpl(
  artifactRoot:    Path,
  maxDiskBytes:    Long = 500L * 1024 * 1024, // 500 MB default
) extends ArtifactSink {

  // Spec §12.3: 1 MB threshold for gzip compression
  private val COMPRESSION_THRESHOLD = 1024L * 1024

  // Spec §12.3: Essential artifact types — always written regardless of budget
  private val essentialTypes = Set(
    "StructuredVerdict", "FailurePacketArtifact", "FinalReport", "AttemptReport",
    "Screenshot", "PatchDiff",
  )

  @volatile private var totalBytesWritten: Long = 0

  override def writeArtifact(
    runId:             String,
    attemptNumber:     Option[Int],
    artifactType:      String,
    producerComponent: String,
    content:           Array[Byte],
    relativePath:      String,
    contentType:       String,
    logicalScope:      Option[String],
    label:             Option[String],
    metadata:          Map[String, String],
  ): ArtifactRecord = {

    // Spec §12.3: Disk budget enforcement — skip non-essential artifacts when budget exceeded
    if (!isEssential(artifactType) && remainingBudgetBytes < content.length) {
      throw new RuntimeException(
        s"Artifact disk budget exceeded: ${totalBytesWritten}/$maxDiskBytes bytes used, " +
        s"cannot write ${content.length} bytes for non-essential $artifactType")
    }

    // Spec §12.3: Compress artifacts > 1 MB
    val (writeData, compressed, compFormat, finalRelPath) =
      if (content.length > COMPRESSION_THRESHOLD) {
        val baos = new ByteArrayOutputStream()
        val gzip = new GZIPOutputStream(baos)
        gzip.write(content)
        gzip.close()
        val gzData = baos.toByteArray
        (gzData, true, Some("gzip"), relativePath + ".gz")
      } else {
        (content, false, None, relativePath)
      }

    val finalPath = artifactRoot.resolve(finalRelPath)
    Files.createDirectories(finalPath.getParent)

    // Spec §12.3: Temp-file-then-rename for atomic writes
    val tmpPath = finalPath.resolveSibling(finalPath.getFileName.toString + ".tmp." + UUID.randomUUID().toString.take(8))
    Files.write(tmpPath, writeData)
    Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE)

    val checksum = sha256(writeData)
    totalBytesWritten += writeData.length

    ArtifactRecord(
      artifactId        = UUID.randomUUID().toString,
      runId             = runId,
      attemptNumber     = attemptNumber,
      artifactType      = ArtifactTypeCodec.fromString(artifactType),
      producerComponent = producerComponent,
      logicalScope      = logicalScope,
      relativePath      = finalRelPath,
      contentType       = if (compressed) "application/gzip" else contentType,
      sizeBytes         = writeData.length.toLong,
      checksumSha256    = checksum,
      compressed        = compressed,
      compressionFormat = compFormat,
      createdAt         = Instant.now(),
      metadata          = metadata ++ label.map("label" -> _),
    )
  }

  override def registerExternalArtifact(
    runId:             String,
    attemptNumber:     Option[Int],
    artifactType:      String,
    producerComponent: String,
    relativePath:      String,
    contentType:       String,
    sizeBytes:         Long,
    checksumSha256:    String,
    logicalScope:      Option[String],
    label:             Option[String],
    metadata:          Map[String, String],
  ): ArtifactRecord = {
    totalBytesWritten += sizeBytes
    val compressed = relativePath.endsWith(".gz")

    ArtifactRecord(
      artifactId        = UUID.randomUUID().toString,
      runId             = runId,
      attemptNumber     = attemptNumber,
      artifactType      = ArtifactTypeCodec.fromString(artifactType),
      producerComponent = producerComponent,
      logicalScope      = logicalScope,
      relativePath      = relativePath,
      contentType       = contentType,
      sizeBytes         = sizeBytes,
      checksumSha256    = checksumSha256,
      compressed        = compressed,
      compressionFormat = if (compressed) Some("gzip") else None,
      createdAt         = Instant.now(),
      metadata          = metadata ++ label.map("label" -> _),
    )
  }

  override def verifyChecksum(record: ArtifactRecord): Boolean = {
    val path = artifactRoot.resolve(record.relativePath)
    if (!Files.exists(path)) return false
    val data = Files.readAllBytes(path)
    val computed = sha256(data)
    computed == record.checksumSha256
  }

  override def absolutePath(relativePath: String): Path =
    artifactRoot.resolve(relativePath)

  override def remainingBudgetBytes: Long =
    math.max(0, maxDiskBytes - totalBytesWritten)

  override def isEssential(artifactType: String): Boolean =
    essentialTypes.contains(artifactType)

  private def sha256(data: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(data)
    digest.digest().map("%02x".format(_)).mkString
  }

}
