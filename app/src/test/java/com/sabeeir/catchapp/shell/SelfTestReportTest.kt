package com.sabeeir.catchapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestReportTest {

    private val fullReport =
        "v=1 uid=2000 pid=4242 hiddenApi=1 im=1 setDisp=1 inject=1 shell=1"

    @Test
    fun `parses a complete report`() {
        val report = SelfTestReport.parse(fullReport)!!
        assertEquals(1, report.version)
        assertEquals(2000, report.uid)
        assertEquals(4242, report.pid)
        assertTrue(report.hiddenApiBypass)
        assertTrue(report.inputManagerInstance)
        assertTrue(report.setDisplayId)
        assertTrue(report.inject)
        assertTrue(report.shellFallback)
        assertTrue(report.inputManagerPath)
        assertTrue(report.canInject)
        assertTrue(report.isShellIdentity)
        assertNull(report.error)
    }

    @Test
    fun `report without the display id hook cannot use the input manager path`() {
        val report = SelfTestReport.parse("v=1 uid=2000 pid=1 hiddenApi=1 im=1 setDisp=0 inject=1 shell=1")!!
        assertFalse(report.inputManagerPath)
        assertTrue(report.canInject) // shell fallback still works
    }

    @Test
    fun `report with no usable path reports no injection capability`() {
        val report = SelfTestReport.parse("v=1 uid=2000 pid=1 hiddenApi=0 im=0 setDisp=0 inject=0 shell=0")!!
        assertFalse(report.inputManagerPath)
        assertFalse(report.canInject)
    }

    @Test
    fun `root identity is recognised`() {
        val report = SelfTestReport.parse("v=1 uid=0 pid=9 hiddenApi=1 im=1 setDisp=1 inject=1 shell=1")!!
        assertFalse(report.isShellIdentity)
        assertTrue(report.canInject)
    }

    @Test
    fun `errors are surfaced`() {
        val report = SelfTestReport.parse(
            "v=1 uid=2000 pid=1 hiddenApi=0 im=0 setDisp=0 inject=0 shell=1 error=setDisplayId_unavailable",
        )!!
        assertEquals("setDisplayId_unavailable", report.error)
        assertTrue(report.summary().contains("setDisplayId_unavailable"))
    }

    @Test
    fun `garbage is rejected instead of producing a half truth`() {
        assertNull(SelfTestReport.parse(null))
        assertNull(SelfTestReport.parse(""))
        assertNull(SelfTestReport.parse("hello world"))
        assertNull(SelfTestReport.parse("uid=2000 pid=1")) // missing version
    }

    @Test
    fun `summary mentions both paths`() {
        val report = SelfTestReport.parse(fullReport)!!
        val summary = report.summary()
        assertTrue(summary.contains("inputManager=ready"))
        assertTrue(summary.contains("shellFallback=ready"))
        assertTrue(summary.contains("shell identity"))
    }
}
