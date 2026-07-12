package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.qualcomm.meshmind.core.dependency.ServiceLocator
import com.qualcomm.meshmind.databinding.FragmentGenericPlaceholderBinding
import com.qualcomm.meshmind.packet.PacketManager
import com.qualcomm.meshmind.repository.PacketHistoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PacketInspectorFragment : BaseFragment() {

    private var binding: FragmentGenericPlaceholderBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentGenericPlaceholderBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.title = "Mesh Frame Inspector"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        binding!!.tvExecutionPlane.text = "COMMUNICATION DATA PLANE"
        binding!!.tvDescription.text = "Displaying frame structures, active serializations, checksum hashes, and packet retransmissions."

        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                try {
                    val pktMgr = ServiceLocator.get(PacketManager::class.java)
                    val historyRepo = ServiceLocator.get(PacketHistoryRepository::class.java)
                    val recentPackets = historyRepo.getRecentPackets(10)
                    
                    val sb = StringBuilder()
                    sb.append("MMF Packet Counter Metrics:\n")
                    sb.append("----------------------------------------\n")
                    sb.append("• Routing Attempts: ${pktMgr.routingAttempts.get()}\n")
                    sb.append("• Transport Send Attempts: ${pktMgr.transportSendAttempts.get()}\n")
                    sb.append("• Transport Send Successes: ${pktMgr.transportSendSuccesses.get()}\n")
                    sb.append("• Received Packets: ${pktMgr.receivedPackets.get()}\n")
                    sb.append("• Failed Packets: ${pktMgr.failedPackets.get()}\n")
                    sb.append("• Retransmitted Packets: ${pktMgr.retransmittedPackets.get()}\n\n")
                    
                    sb.append("Recent Packet Logs (SQLite):\n")
                    sb.append("========================================\n")
                    if (recentPackets.isEmpty()) {
                        sb.append("No local packet transactions captured yet.\n\n")
                    } else {
                        for (p in recentPackets) {
                            val timeStr = dateFormat.format(Date(p.timestamp))
                            sb.append("[$timeStr] ID: ${p.packetId.take(8)}...\n")
                            sb.append("  ↳ Src: ${p.sourceNodeId} | Dest: ${p.destinationNodeId}\n")
                            sb.append("  ↳ Hops: ${p.hopCount} | TTL: ${p.ttl} | Status: ${p.status}\n")
                            sb.append("----------------------------------------\n")
                        }
                        sb.append("\n")
                    }
                    
                    sb.append("Last Chat Delivery Trace:\n")
                    sb.append("========================================\n")
                    val traces = com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.getRecentTraces()
                    if (traces.isEmpty()) {
                        sb.append("No user chat messages sent yet.")
                    } else {
                        for (t in traces) {
                            sb.append(t.getFormattedLine()).append("\n")
                            sb.append("----------------------------------------\n")
                        }
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
