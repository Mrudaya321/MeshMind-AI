package com.qualcomm.meshmind.classification

import com.qualcomm.meshmind.classification.models.EmergencyResponseRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmergencyDepartmentMapperTest {

    @Test
    fun testAuthoritativeLabelsMapToCorrectRoles() {
        assertEquals(EmergencyResponseRole.FIRE_DEPARTMENT, EmergencyDepartmentMapper.mapClassToDepartment("Fire"))
        assertEquals(EmergencyResponseRole.DISASTER_RESPONSE, EmergencyDepartmentMapper.mapClassToDepartment("Flood"))
        assertEquals(EmergencyResponseRole.DISASTER_RESPONSE, EmergencyDepartmentMapper.mapClassToDepartment("Earthquake"))
        assertEquals(EmergencyResponseRole.DISASTER_RESPONSE, EmergencyDepartmentMapper.mapClassToDepartment("Storm"))
        assertEquals(EmergencyResponseRole.RESCUE_RESPONSE, EmergencyDepartmentMapper.mapClassToDepartment("Building Collapse"))
        assertEquals(EmergencyResponseRole.MEDICAL_RESPONSE, EmergencyDepartmentMapper.mapClassToDepartment("Medical Emergency"))
        assertEquals(EmergencyResponseRole.SECURITY_RESPONSE, EmergencyDepartmentMapper.mapClassToDepartment("Security Threat"))
        assertEquals(EmergencyResponseRole.HAZMAT_RESPONSE, EmergencyDepartmentMapper.mapClassToDepartment("Chemical Explosion"))
    }

    @Test
    fun testUnknownLabelFailsToMap() {
        // Unknown labels should not fallback to CIVILIAN, they must return null.
        assertNull(EmergencyDepartmentMapper.mapClassToDepartment("Unknown Crisis"))
        assertNull(EmergencyDepartmentMapper.mapClassToDepartment("Alien Invasion"))
        assertNull(EmergencyDepartmentMapper.mapClassToDepartment("Infrastructure Failure"))
        assertNull(EmergencyDepartmentMapper.mapClassToDepartment(""))
    }
}
