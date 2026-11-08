package com.underscanner.theunderscannerapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the backend's SLAM-profile JSON (shapes as app.py v2.3 sends them). */
class SlamModelsTest {

    private val scanRow = """
        {"name":"2026-11-08_Test_01","date":"2026-11-08","location":"Test","run":"01",
         "bag":{"present":true,"size_bytes":10,"size_human":"10.0 B"},
         "pcd":{"present":true,"size_bytes":10,"size_human":"10.0 B"},
         "config":{"present":true},"notes":{"present":false,"text":""},
         "trajectory":{"present":false},"health":{"present":false},
         "slam":{"algo":"fastlio2","algo_label":"FAST-LIO2","profile":"voxel-0.3",
                 "overrides":{"mapping.det_range":60.0,"preprocess.blind":0.3},
                 "sha256":"abcdef0123456789"}}
    """.trimIndent()

    @Test fun scanRowCarriesSlamMeta() {
        val s = ScanInfo.from(JSONObject(scanRow))
        val m = s.slam!!
        assertEquals("FAST-LIO2 · voxel-0.3", m.title)
        assertEquals(listOf("mapping.det_range" to "60.0", "preprocess.blind" to "0.3"), m.overrides)
        assertEquals("mapping.det_range=60.0, preprocess.blind=0.3", m.overridesText)
        assertEquals("abcdef0123456789", m.sha256)
    }

    @Test fun legacyScanHasNoMeta() {
        val o = JSONObject(scanRow).put("slam", JSONObject.NULL)
        assertNull(ScanInfo.from(o).slam)
        assertNull(ScanInfo.from(JSONObject(scanRow).apply { remove("slam") }).slam)
    }

    @Test fun statusAndStartCarrySlam() {
        val st = ScanStatus.from(JSONObject("""{"running":true,"scan":"x","slam":
            {"algo":"fastlio2","profile":"usine","overrides":{}}}"""))
        assertEquals("usine", st.slam!!.profile)
        assertTrue(st.slam!!.overrides.isEmpty())
        assertNull(ScanStatus.from(JSONObject("""{"running":false,"slam":null}""")).slam)
        assertEquals("p", StartResult.from(JSONObject(
            """{"scan":"x","slam":{"algo":"a","profile":"p","overrides":{}}}""")).slam!!.profile)
    }

    @Test fun validationResult() {
        val v = SlamValidation.from(JSONObject("""
            {"ok":false,"issues":[
              {"level":"error","msg":"clé inconnue : nope","line":1,"where":"overrides"},
              {"level":"warning","msg":"x absente","line":null,"where":"yaml"}]}"""))
        assertFalse(v.ok)
        assertEquals(1, v.errors.size)
        assertEquals(1, v.errors[0].line)
        assertEquals("overrides", v.errors[0].where)
        assertNull(v.warnings[0].line)
        assertNull(v.effective)
    }

    @Test fun editorLineStart() {
        val t = "a: 1\nbb: 2\n\nc: 3"
        assertEquals(0, lineStart(t, 1))
        assertEquals(5, lineStart(t, 2))
        assertEquals(11, lineStart(t, 3))
        assertEquals(12, lineStart(t, 4))
        assertEquals(t.length, lineStart(t, 99))
    }

    @Test fun profileNameCheck() {
        assertNull(SlamViewModel.nameProblem("voxel-0.3"))
        assertNull(SlamViewModel.nameProblem("Résidence_2"))
        assertTrue(SlamViewModel.nameProblem(".x") != null)
        assertTrue(SlamViewModel.nameProblem("a/b") != null)
        assertTrue(SlamViewModel.nameProblem("usine") != null)
        assertTrue(SlamViewModel.nameProblem("x".repeat(65)) != null)
    }

    @Test fun algosAndProfiles() {
        val a = SlamAlgos.from(JSONObject("""
            {"default":"fastlio2","algos":[{"id":"fastlio2","label":"FAST-LIO2","description":"d",
             "forced":{"map_file_path":"/x","pcd_save.interval":-1},"live":"accumulate",
             "default_profile":"voxel-0.3"}]}"""))
        assertEquals("voxel-0.3", a.byId("fastlio2")!!.defaultProfile)
        assertEquals(listOf("map_file_path", "pcd_save.interval"), a.byId("fastlio2")!!.forced)
        val p = SlamProfileInfo.from(JSONObject(
            """{"name":"usine","readonly":true,"is_default":false,"updated":"2026-11-08T10:00:00"}"""))
        assertTrue(p.readonly)
        assertEquals(SlamProfileInfo.FACTORY, p.name)
    }
}
