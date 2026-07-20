package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.databinding.FragmentObserverGatewayBinding
import com.qualcomm.meshmind.observer.ObserverGatewayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 13 Mesh Observer Gateway UI.
 * Connects to the ObserverGatewayService to visualize real-time NOC connectivity status.
 */
class ObserverFragment : BaseFragment() {

    private var binding: FragmentObserverGatewayBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentObserverGatewayBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        val service = ServiceLocator.get(ObserverGatewayService::class.java)
        
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                service.discoveryState.collect { state ->
                    binding?.tvDiscoveryState?.text = "Observer Discovery: $state"
                }
            }
            launch {
                service.endpoint.collect { endpoint ->
                    binding?.tvEndpoint?.text = if (endpoint != null) "Observer Endpoint: ${endpoint.host}:${endpoint.port}" else "Observer Endpoint: UNAVAILABLE"
                }
            }
            launch {
                service.connectionState.collect { state ->
                    binding?.tvGatewayState?.text = "Gateway State: $state"
                }
            }
            launch {
                service.lastFailureReason.collect { reason ->
                    binding?.tvFailureReason?.text = "Last Observer Failure Reason: $reason"
                }
            }
            
            // Poll counters
            launch {
                while (true) {
                    binding?.tvForwardedCount?.text = "Forwarded Observation Count: ${service.getForwardedCount()}"
                    binding?.tvDroppedCount?.text = "Dropped Observer Copy Count: ${service.getDroppedCount()}"
                    delay(1000)
                }
            }
        }
        
        binding!!.swEnableObserver.setOnCheckedChangeListener { _, isChecked ->
            // TODO: Enable/disable observer forwarding policy
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
