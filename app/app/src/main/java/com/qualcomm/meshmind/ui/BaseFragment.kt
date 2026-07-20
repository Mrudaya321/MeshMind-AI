package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * Baseline Fragment that wraps UI messaging hooks and navigation controllers in Kotlin.
 */
abstract class BaseFragment : Fragment() {

    protected val logTag: String = javaClass.simpleName

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MeshLogger.d(logTag, "onViewCreated: Fragment view bound")
    }

    protected fun getNavController(): NavController {
        return findNavController()
    }

    protected fun showMessage(msg: String?) {
        view?.let {
            if (msg != null) {
                Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    protected fun showError(errorMsg: String?) {
        view?.let {
            if (errorMsg != null) {
                Snackbar.make(it, "[ERROR] $errorMsg", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MeshLogger.d(logTag, "onDestroyView: Fragment view destroyed")
    }
}
