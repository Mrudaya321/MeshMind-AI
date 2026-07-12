package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.qualcomm.meshmind.core.runtime.SubsystemManager
import com.qualcomm.meshmind.databinding.FragmentGenericPlaceholderBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeveloperConsoleFragment : BaseFragment() {

    private var binding: FragmentGenericPlaceholderBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentGenericPlaceholderBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.title = "Qualcomm Developer Console"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        binding!!.tvExecutionPlane.text = "MONITORING PLANE (Passive)"
        binding!!.tvDescription.text = "Displays real-time diagnostic reports and operational status for all registered edge subsystems."

        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    val manager = SubsystemManager.getInstance()
                    val healthReports = manager.getSubsystemsHealth()
                    
                    val sb = StringBuilder()
                    sb.append("Edge Subsystem Health Diagnostics:\n")
                    sb.append("========================================\n\n")
                    
                    for (report in healthReports) {
                        val statusStr = if (report.isOperational) "ONLINE" else "OFFLINE"
                        val timeStr = dateFormat.format(Date(report.lastExecutionTimestamp))
                        
                        sb.append("• Subsystem: ${report.subsystemName}\n")
                        sb.append("  ↳ State: $statusStr\n")
                        sb.append("  ↳ Errors: ${report.errorCount}\n")
                        sb.append("  ↳ Diagnostic: ${report.diagnosticMessage}\n")
                        sb.append("  ↳ Updated: $timeStr\n")
                        sb.append("----------------------------------------\n\n")
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
