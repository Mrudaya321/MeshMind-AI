package com.qualcomm.meshmind.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.qualcomm.meshmind.logging.MeshLogger

/**
 * Baseline Activity that wraps common layout structures and lifecycle monitoring logs in Kotlin.
 */
abstract class BaseActivity : AppCompatActivity() {

    protected val logTag: String = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshLogger.d(logTag, "onCreate: Activity created")
    }

    override fun onStart() {
        super.onStart()
        MeshLogger.d(logTag, "onStart: Activity started")
    }

    override fun onResume() {
        super.onResume();
        MeshLogger.d(logTag, "onResume: Activity active")
    }

    override fun onPause() {
        super.onPause();
        MeshLogger.d(logTag, "onPause: Activity standby")
    }

    override fun onStop() {
        super.onStop();
        MeshLogger.d(logTag, "onStop: Activity stopped")
    }

    override fun onDestroy() {
        super.onDestroy();
        MeshLogger.d(logTag, "onDestroy: Activity destroyed")
    }
}
