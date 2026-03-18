package demiurge.persistence

import munit.FunSuite
import java.nio.file.Files

class DatabaseSuite extends FunSuite {

  // Rule 11: Use real SQLite files on disk, create temp per test, delete after

  test("Database creation enables WAL mode") {
    val tmp = Files.createTempFile("demiurge-test-", ".db")
    Files.delete(tmp) // ensure fresh
    try {
      val conn = Database.open(tmp)
      try {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("PRAGMA journal_mode")
        assert(rs.next())
        assertEquals(rs.getString(1), "wal")
        rs.close()
        stmt.close()
      } finally {
        conn.close()
      }
    } finally {
      Files.deleteIfExists(tmp)
      // WAL mode creates -wal and -shm files
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-wal"))
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-shm"))
    }
  }

  test("Database creation enables foreign keys") {
    val tmp = Files.createTempFile("demiurge-test-", ".db")
    Files.delete(tmp)
    try {
      val conn = Database.open(tmp)
      try {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("PRAGMA foreign_keys")
        assert(rs.next())
        assertEquals(rs.getInt(1), 1)
        rs.close()
        stmt.close()
      } finally {
        conn.close()
      }
    } finally {
      Files.deleteIfExists(tmp)
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-wal"))
      Files.deleteIfExists(tmp.resolveSibling(tmp.getFileName.toString + "-shm"))
    }
  }
}
