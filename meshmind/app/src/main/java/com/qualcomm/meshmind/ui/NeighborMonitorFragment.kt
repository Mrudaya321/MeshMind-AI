package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.qualcomm.meshmind.R
import com.qualcomm.meshmind.models.NeighborNodeState
import com.qualcomm.meshmind.state.NeighborStateRepository
import kotlinx.coroutines.launch

class NeighborMonitorFragment : BaseFragment() {

    private var rvNeighbors: RecyclerView? = null
    private var tvEmptyState: TextView? = null
    private var adapter: NeighborAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Need to use the layout we just created. But if it doesn't exist in R.layout, 
        // we'll just parse the view manually or inflate it if the R class is updated.
        // For simplicity, inflating layout matching fragment_neighbor_monitor.xml
        return inflater.inflate(R.layout.fragment_neighbor_monitor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.apply {
            setNavigationOnClickListener { getNavController().popBackStack() }
        }
        
        rvNeighbors = view.findViewById(R.id.rv_neighbors)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        
        rvNeighbors?.layoutManager = LinearLayoutManager(context)
        adapter = NeighborAdapter(emptyList())
        rvNeighbors?.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NeighborStateRepository.getInstance().neighborListFlow.collect { neighbors ->
                    if (neighbors.isEmpty()) {
                        rvNeighbors?.visibility = View.GONE
                        tvEmptyState?.visibility = View.VISIBLE
                    } else {
                        rvNeighbors?.visibility = View.VISIBLE
                        tvEmptyState?.visibility = View.GONE
                        adapter?.updateData(neighbors)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rvNeighbors = null
        tvEmptyState = null
        adapter = null
    }

    class NeighborAdapter(private var items: List<NeighborNodeState>) : RecyclerView.Adapter<NeighborAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNodeId: TextView = view.findViewById(R.id.tv_node_id)
            val tvRssi: TextView = view.findViewById(R.id.tv_rssi)
            val tvBattery: TextView = view.findViewById(R.id.tv_battery)
            val chipStatus: Chip = view.findViewById(R.id.chip_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_neighbor_node, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvNodeId.text = item.nodeId
            holder.tvRssi.text = "${item.rssi} dBm"
            holder.tvBattery.text = item.batteryLevel?.let { "$it%" } ?: "Unknown"
            
            // Connect to real runtime routing states to determine if this neighbor is connected or just discovered
            // Currently this represents a genuine BLE discovered neighbor.
            holder.chipStatus.text = "Discovered" 
            holder.tvBattery.visibility = View.VISIBLE
        }

        override fun getItemCount() = items.size
        
        fun updateData(newItems: List<NeighborNodeState>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
