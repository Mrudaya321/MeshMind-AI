package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qualcomm.meshmind.R
import com.qualcomm.meshmind.databinding.FragmentDashboardBinding
import com.qualcomm.meshmind.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import android.widget.Toast

/**
 * Main dashboard UI reporting execution plane status in Kotlin.
 */
class DashboardFragment : BaseFragment() {

    private var binding: FragmentDashboardBinding? = null
    private lateinit var viewModel: DashboardViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[DashboardViewModel::class.java]

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.meshStatus.collect { status ->
                        binding?.tvMeshStatus?.text = status
                    }
                }
                launch {
                    viewModel.discoveredPeersCount.collect { count ->
                        binding?.tvDiscoveredPeers?.text = count.toString()
                    }
                }
                launch {
                    viewModel.connectedPeersCount.collect { count ->
                        binding?.tvConnectedPeers?.text = count.toString()
                    }
                }
                launch {
                    viewModel.buildMeshMessage.collect { msg ->
                        msg?.let {
                            Toast.makeText(requireContext(), "Build Mesh: $it", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        binding!!.btnStartDiscovery.setOnClickListener {
            viewModel.startDiscovery()
        }
        
        binding!!.btnBuildMesh.setOnClickListener {
            viewModel.buildMesh()
        }

        // Connect dashboard triggers
        binding!!.btnConversations.setOnClickListener {
            getNavController().navigate(R.id.action_dashboard_to_conversations)
        }

        binding!!.btnEmergency.setOnClickListener {
            getNavController().navigate(R.id.action_dashboard_to_emergency)
        }

        binding!!.btnNeighborMonitor.setOnClickListener {
            getNavController().navigate(R.id.action_dashboard_to_neighborMonitor)
        }

        binding!!.btnMeshObserver.setOnClickListener {
            getNavController().navigate(R.id.action_dashboard_to_meshObserver)
        }

        binding!!.btnPacketInspector.setOnClickListener {
            getNavController().navigate(R.id.action_dashboard_to_packetInspector)
        }

        binding!!.btnSettings.setOnClickListener {
            getNavController().navigate(R.id.action_dashboard_to_settings)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
