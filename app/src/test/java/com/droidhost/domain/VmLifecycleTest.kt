package com.droidhost.domain

import org.junit.Assert.*
import org.junit.Test

class VmLifecycleTest { @Test fun startsAndStops(){val vm=VmLifecycle();assertTrue(vm.start());assertEquals(VmState.STARTING,vm.state);vm.bootSucceeded();assertEquals(VmState.RUNNING,vm.state);assertTrue(vm.stop());vm.stopped();assertEquals(VmState.STOPPED,vm.state)}; @Test fun failedVmCanRetry(){val vm=VmLifecycle();vm.start();vm.bootFailed();assertEquals(VmState.FAILED,vm.state);assertTrue(vm.start())}; @Test fun duplicateCommandsRejected(){val vm=VmLifecycle();assertFalse(vm.stop());assertTrue(vm.start());assertFalse(vm.start())} }
