package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.databinding.FragmentGenericPlaceholderBinding
import com.qualcomm.meshmind.repository.TelemetryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelemetryViewerFragment : BaseFragment() {

    private var binding: FragmentGenericPlaceholderBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentGenericPlaceholderBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.title = "Edge Telemetry Logs"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        binding!!.tvExecutionPlane.text = "BACKGROUND AI CONTROL PLANE"
        binding!!.tvDescription.text = "Raw battery levels, CPU speed metrics, and wireless network statistics captured periodically."

        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    val telemetryRepo = ServiceLocator.get(TelemetryRepository::class.java)
                    val telemetryList = telemetryRepo.getRecentTelemetry(10)
                    
                    val sb = StringBuilder()
                    sb.append("Recent Telemetry Snapshots (SQLite):\n")
                    sb.append("========================================\n")
                    if (telemetryList.isEmpty()) {
                        sb.append("No telemetry snapshots recorded yet. Telemetry sweeps run every 12 seconds.")
                    } else {
                        for (t in telemetryList) {
                            val timeStr = dateFormat.format(Date(t.timestamp))
                            sb.append("[$timeStr] Node Telemetry Log:\n")
                            sb.append("  • Battery Level: ${t.batteryLevel}%\n")
                            sb.append("  • Wi-Fi RSSI: ${t.wifiRssi} dBm\n")
                            sb.append("  • Wi-Fi Connected: ${if (t.isWifiConnected) "Yes" else "No"}\n")
                            sb.append("  • BLE Neighbors: ${t.bluetoothNeighborCount}\n")
                            sb.append("  • CPU Sweep Delay: ${t.cpuExecutionDelayMs} ms\n")
                            sb.append("----------------------------------------\n")
                        }
                    }
                    binding?.tvStatusInfo?.text = sb.toString()
                } catch (ignored: Exception) {}
                delay(2500)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
