package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qualcomm.meshmind.R
import com.qualcomm.meshmind.databinding.FragmentSplashBinding
import com.qualcomm.meshmind.state.ApplicationState
import kotlinx.coroutines.launch

/**
 * Fragment displaying loading status while platform subsystems initialize.
 */
class SplashFragment : BaseFragment() {

    private var binding: FragmentSplashBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentSplashBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val coordinator = com.qualcomm.meshmind.core.runtime.RuntimeCoordinator.getInstance(requireContext())

        // Monitor platform bootstrapper progress reactively using Kotlin flow collection
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    coordinator.initProgress.collect { progress ->
                        binding?.progressBar?.isIndeterminate = false
                        binding?.progressBar?.setProgress(progress, true)
                    }
                }
                launch {
                    coordinator.currentInitializingSubsystem.collect { subsystemName ->
                        if (subsystemName.isNotEmpty()) {
                            binding?.tvStatus?.text = "Initializing $subsystemName..."
                        }
                    }
                }
                launch {
                    ApplicationState.getInstance().isSubsystemsInitialized.collect { initialized ->
                        if (initialized) {
                            getNavController().navigate(R.id.action_splash_to_dashboard)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
