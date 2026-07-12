package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.qualcomm.meshmind.databinding.FragmentEmergencyBroadcastBinding
import com.qualcomm.meshmind.classification.EmergencyBroadcastManager
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.widget.Toast

class EmergencyFragment : BaseFragment() {

    private var binding: FragmentEmergencyBroadcastBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentEmergencyBroadcastBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.title = "Emergency Broadcast"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        binding!!.btnClassifyBroadcast.setOnClickListener {
            val text = binding!!.etEmergencyInput.text.toString()
            if (text.isNotBlank()) {
                executeBroadcast(text)
            } else {
                Toast.makeText(requireContext(), "Please enter emergency description", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun executeBroadcast(text: String) {
        binding!!.progressBar.visibility = View.VISIBLE
        binding!!.btnClassifyBroadcast.isEnabled = false
        binding!!.cardResult.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            val result = EmergencyBroadcastManager.broadcastEmergency(text)
            
            binding!!.progressBar.visibility = View.GONE
            binding!!.btnClassifyBroadcast.isEnabled = true
            binding!!.cardResult.visibility = View.VISIBLE
            
            if (result.success) {
                binding!!.tvResultType.text = "Emergency Type: ${result.predictedClassLabel}"
                binding!!.tvResultConfidence.text = "AI Confidence: ${result.confidence}"
                binding!!.tvResultDepartment.text = "Target Department: ${result.targetRole?.name}"
                binding!!.tvResultDuration.text = "Inference Duration: ${result.inferenceDurationMs}ms"
                binding!!.tvResultStatus.text = "Broadcast Status: ENQUEUED"
                binding!!.tvDiagnosticReason.visibility = View.GONE
                binding!!.tvRuntimeState.visibility = View.GONE
            } else {
                binding!!.tvResultType.text = "Emergency Type: ${result.predictedClassLabel ?: "UNKNOWN"}"
                binding!!.tvResultConfidence.text = "AI Confidence: ${result.confidence ?: "N/A"}"
                binding!!.tvResultDepartment.text = "Target Department: ${result.targetRole?.name ?: "N/A"}"
                binding!!.tvResultDuration.text = "Inference Duration: ${result.inferenceDurationMs}ms"
                binding!!.tvResultStatus.text = "Broadcast Status: ${result.status}"
                
                binding!!.tvDiagnosticReason.visibility = View.VISIBLE
                binding!!.tvDiagnosticReason.text = "Diagnostic Reason: ${result.diagnosticReason ?: "N/A"}"
                
                binding!!.tvRuntimeState.visibility = View.VISIBLE
                binding!!.tvRuntimeState.text = "Runtime State: ${result.runtimeState}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
