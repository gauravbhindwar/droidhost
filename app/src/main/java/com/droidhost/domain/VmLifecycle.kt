package com.droidhost.domain

class VmLifecycle { var state:VmState = VmState.STOPPED; private set
 fun start():Boolean { if(state != VmState.STOPPED && state != VmState.FAILED)return false; state=VmState.STARTING; return true }
 fun bootSucceeded(){ check(state==VmState.STARTING); state=VmState.RUNNING }
 fun bootFailed(){ check(state==VmState.STARTING); state=VmState.FAILED }
 fun stop():Boolean { if(state!=VmState.RUNNING)return false; state=VmState.STOPPING; return true }
 fun stopped(){ check(state==VmState.STOPPING); state=VmState.STOPPED }
 fun failed(){ state=VmState.FAILED }
}
