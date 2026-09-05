package com.droidhost.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidhost.data.AgentRepository
import com.droidhost.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardState(val metrics:Metrics=Metrics(),val vmState:VmState=VmState.STOPPED,val containers:List<Container> = emptyList(),val images:Int=0,val volumes:Int=0,val networks:Int=0,val loading:Boolean=true,val error:String?=null)
class MainViewModel(private val repository:AgentRepository):ViewModel() {
 private val _state=MutableStateFlow(DashboardState()); val state:StateFlow<DashboardState> = _state.asStateFlow()
 init { refreshLoop() }
 private fun refreshLoop()=viewModelScope.launch { while(true){ refresh(); delay(3000) } }
 fun refresh()=viewModelScope.launch { try { val m=repository.metrics(); val c=repository.containers(); _state.value=_state.value.copy(metrics=m,containers=c,images=repository.images().size,volumes=repository.volumes().size,networks=repository.networks().size,loading=false,error=null) } catch(e:Exception){_state.value=_state.value.copy(loading=false,error=e.message ?: ServerFailure.AgentUnavailable.userMessage)} }
 fun action(id:String,action:String)=viewModelScope.launch { runCatching { repository.action(id,action); refresh() }.onFailure { _state.value=_state.value.copy(error=it.message) } }
}
