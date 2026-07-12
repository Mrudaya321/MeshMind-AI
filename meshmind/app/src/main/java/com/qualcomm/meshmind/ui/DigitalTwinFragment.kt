package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.databinding.FragmentGenericPlaceholderBinding
import com.qualcomm.meshmind.digitaltwin.communication.DigitalTwinClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DigitalTwinFragment : BaseFragment() {

    private var binding: FragmentGenericPlaceholderBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentGenericPlaceholderBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.title = "Digital Twin Sync"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        binding!!.tvExecutionPlane.text = "MONITORING PLANE (Passive)"
        binding!!.tvDescription.text = "Synchronizes telemetry, diagnostics, and mesh network structures to the observing Snapdragon AI PC."

        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    val client = ServiceLocator.get(DigitalTwinClient::class.java)
                    val isConnected = client.isConnected()
                    val cacheSize = client.getCacheSize()
                    
                    val sb = StringBuilder()
                    sb.append("Snapdragon Twin Server Status:\n")
                    sb.append("----------------------------------------\n")
                    sb.append("• Host Address: 192.168.1.100\n")
                    sb.append("• Sync Port: 9090\n")
                    sb.append("• Connection State: ${if (isConnected) "CONNECTED" else "OFFLINE (Searching...)"}\n")
                    sb.append("• Local Cached Snapshots: $cacheSize\n\n")
                    
                    sb.append("Synchronization Logs:\n")
                    sb.append("========================================\n")
                    if (isConnected) {
                        sb.append("• [Success] Socket stream established\n")
                        sb.append("• [Success] Streaming live telemetry packets\n")
                    } else {
                        sb.append("• [Warning] AI PC server is unreachable\n")
                        sb.append("• [Info] Telemetry is being cached locally in memory queue\n")
                        sb.append("• [Info] Auto-flushing will resume once connection recovers\n")
                    }
                    binding?.tvStatusInfo?.text = sb.toString()
                } catch (ignored: Exception) {}
                delay(2000)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
