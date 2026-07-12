package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.qualcomm.meshmind.databinding.FragmentGenericPlaceholderBinding

class AboutFragment : BaseFragment() {

    private var binding: FragmentGenericPlaceholderBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentGenericPlaceholderBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding!!.toolbar.title = "About MeshMind"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }
        
        binding!!.tvExecutionPlane.text = "MONITORING PLANE (Passive)"
        binding!!.tvDescription.text = "MeshMind Edge Node v1.0.0. Optimized for Snapdragon processors and Hexagon NPUs."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
