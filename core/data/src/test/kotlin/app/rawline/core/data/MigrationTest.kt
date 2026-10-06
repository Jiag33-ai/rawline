package app.rawline.core.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Host-side migration check without an emulator: builds each version's tables from the committed Room schema JSON in an
 * in-memory SQLite, runs our migration SQL and compares the resulting columns with the next version's schema JSON.
 */
class MigrationTest {
    private val dir = File("schemas/app.rawline.core.data.RawlineDb")

    private fun schema(v: Int) = JSONObject(File(dir, "$v.json").readText()).getJSONObject("database")
    private fun entity(v: Int, table: String): JSONObject {
        val a = schema(v).getJSONArray("entities")
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("tableName") == table) return a.getJSONObject(i)
        throw AssertionError("no $table in v$v")
    }

    private fun create(v: Int): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        val a = schema(v).getJSONArray("entities")
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            c.createStatement().use { it.execute(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))) }
        }
        return c
    }

    private fun run(c: Connection, sql: List<String>) = sql.forEach { s -> c.createStatement().use { it.execute(s) } }

    /** (name, type, notNull, default) per column, ordered, from a live table and from schema JSON. */
    private fun live(c: Connection, table: String): List<List<String>> = c.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { r -> val out = ArrayList<List<String>>(); while (r.next()) out.add(listOf(r.getString("name"), r.getString("type"), (r.getInt("notnull") == 1).toString(), r.getString("dflt_value") ?: "")); out }
    }
    private fun declared(v: Int, table: String): List<List<String>> {
        val f = entity(v, table).getJSONArray("fields")
        return List(f.length()) { val o = f.getJSONObject(it); listOf(o.getString("columnName"), o.getString("affinity"), o.optBoolean("notNull").toString(), o.optString("defaultValue", "")) }
    }

    @Test fun currentVersionMatchesTheCommittedSchema() {
        assertEquals(RawlineDb.VERSION, schema(RawlineDb.VERSION).getInt("version"))
        assertTrue(File(dir, "${RawlineDb.VERSION}.json").exists())
    }

    @Test fun migration3to4KeepsRowsAndMatchesSchema() {
        create(3).use { c ->
            run(c, listOf("INSERT INTO meta (`key`, rating, flag, label) VALUES ('a|1|2', 4, 1, 3)"))
            run(c, RawlineDb.SQL_3_4)
            for (t in listOf("meta", "edits", "photos", "snapshots", "presets", "export_jobs")) assertEquals(t, declared(4, t), live(c, t))
            c.createStatement().use { it.executeQuery("SELECT rating, flag, label, updatedAt FROM meta").use { r ->
                assertTrue(r.next()); assertEquals(4, r.getInt(1)); assertEquals(1, r.getInt(2)); assertEquals(3, r.getInt(3)); assertEquals(0L, r.getLong(4)) } }
        }
    }

    @Test fun downgrade4to3KeepsEveryRowAndMatchesV3() {
        create(4).use { c ->
            run(c, listOf(
                "INSERT INTO meta (`key`, rating, flag, label, updatedAt) VALUES ('a|1|2', 4, 1, 3, 99)",
                "INSERT INTO edits (`key`, json, updatedAt) VALUES ('a|1|2', '{}', 7)",
                "INSERT INTO presets (name, json, createdAt) VALUES ('p', '{}', 1)",
            ))
            run(c, RawlineDb.SQL_4_3)
            assertEquals(declared(3, "meta"), live(c, "meta"))
            fun count(t: String) = c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $t").use { r -> r.next(); r.getInt(1) } }
            assertEquals(1, count("meta")); assertEquals(1, count("edits")); assertEquals(1, count("presets"))
        }
    }

    @Test fun migration2to3CreatesExportJobs() {
        create(3).use { c ->
            run(c, listOf("DROP TABLE export_jobs"))
            run(c, RawlineDb.SQL_2_3)
            assertEquals(declared(3, "export_jobs"), live(c, "export_jobs"))
        }
    }

    @Test fun everyMigrationStepIsRegistered() {
        val steps = RawlineDb.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertTrue((2 to 3) in steps && (3 to 4) in steps && (4 to 3) in steps)
    }
}
