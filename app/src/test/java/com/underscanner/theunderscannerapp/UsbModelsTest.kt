package com.underscanner.theunderscannerapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the backend's USB JSON (shapes as app.py / usbcopy.py send them). */
class UsbModelsTest {

    @Test fun device() {
        val d = UsbDevice.from(JSONObject("""
            {"device":"/dev/sda1","disk":"/dev/sda","label":"ESD-USB","name":"ESD-USB",
             "model":"Cruzer Force","vendor":"SanDisk","size":31259099136,"size_human":"29.1 Go",
             "fstype":"vfat","fsver":"FAT32","mounted":true,"mountpoint":"/media/orin4slam/ESD-USB",
             "free":1000,"free_human":"1000 o","max_file":4294967295}"""))
        assertTrue(d.mounted)
        assertTrue(d.isFat32)
        assertEquals("FAT32", d.fsLabel)
        assertEquals("SanDisk Cruzer Force · 29.1 Go · FAT32", d.details)
        assertEquals(1000L, d.free)

        val u = UsbDevice.from(JSONObject("""
            {"device":"/dev/sdb1","name":"/dev/sdb1","model":null,"vendor":null,"size":1,
             "size_human":"1 o","fstype":"exfat","fsver":null,"mounted":false,"free":null}"""))
        assertFalse(u.mounted)
        assertNull(u.free)
        assertEquals("EXFAT", u.fsLabel)
        assertEquals("1 o · EXFAT", u.details)
    }

    @Test fun transfer() {
        val t = UsbTransfer.from(JSONObject("""
            {"state":"copying","scan":"S","device":"/dev/sda1","label":"ESD-USB","bytes_done":500,
             "bytes_total":1000,"percent":50,"rate_bps":2097152.0,"eta_s":90,"error":null}"""))
        assertTrue(t.active)
        assertEquals("50 % · 2.0 MB/s · reste 1 min 30 s".substringBefore(" ·"), t.progressText().substringBefore(" ·"))
        assertTrue(t.progressText().contains("reste 1 min 30 s"))
        val idle = UsbTransfer.from(JSONObject("""{"state":"idle"}"""))
        assertFalse(idle.active)
        assertEquals(UsbTransfer.IDLE, UsbTransfer.from(null))
        val err = UsbTransfer.from(JSONObject("""{"state":"error","error":"rsync code 23"}"""))
        assertEquals("Échec : rsync code 23", err.progressText())
        assertEquals("Copié sur ESD-USB ✓",
            UsbTransfer.from(JSONObject("""{"state":"done","label":"ESD-USB"}""")).progressText())
    }

    @Test fun planAndPresence() {
        val p = UsbPlan.from(JSONObject("""
            {"ok":false,"problems":["pas assez de place : 2 Go à copier, 1 Go libres sur ESD-USB"],
             "already":false,"bytes_total":2,"total_human":"2 Go","bytes_needed":2,
             "needed_human":"2 Go","files":3,"artifacts":["bags/S"],
             "device":{"device":"/dev/sda1","name":"ESD-USB","fstype":"vfat","mounted":true}}"""))
        assertFalse(p.ok)
        assertEquals(1, p.problems.size)
        assertEquals("ESD-USB", p.device!!.name)

        val row = JSONObject("""{"name":"S","usb":{"present":true,"stick":"ESD-USB"}}""")
        assertEquals(UsbPresence(true, "ESD-USB"), ScanInfo.from(row).usb)
        assertNull(ScanInfo.from(JSONObject("""{"name":"S","usb":null}""")).usb)
    }
}
