package com.droidhost.domain

import org.junit.Assert.*
import org.junit.Test

class ConfigurationTest { private val device=DeviceResources(8,8192,64); @Test fun acceptsRealisticConfig(){assertTrue(validateVmConfiguration(VmConfiguration(4,4096,32,false),device).valid)}; @Test fun rejectsImpossibleCpu(){val result=validateVmConfiguration(VmConfiguration(9,4096,32,false),device);assertFalse(result.valid);assertTrue(result.errors.single().contains("CPU"))}; @Test fun rejectsLowRamAndDisk(){val result=validateVmConfiguration(VmConfiguration(2,256,2,false),device);assertEquals(2,result.errors.size)} }
