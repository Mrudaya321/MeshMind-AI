package com.qualcomm.meshmind.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qualcomm.meshmind.R
import com.qualcomm.meshmind.databinding.FragmentConversationsBinding
import com.qualcomm.meshmind.viewmodel.ConversationsViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationsFragment : BaseFragment() {

    private var binding: FragmentConversationsBinding? = null
    private lateinit var viewModel: ConversationsViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[ConversationsViewModel::class.java]

        binding!!.toolbar.setNavigationOnClickListener {
            getNavController().popBackStack()
        }

        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.conversations.collect { list ->
                    binding!!.layoutConversations.removeAllViews()
                    if (list.isEmpty()) {
                        val tvEmpty = TextView(requireContext()).apply {
                            text = "No active secure conversations.\n\nOnce neighboring edge nodes are discovered via BLE, chat profiles are auto-created."
                            setTextColor(resources.getColor(R.color.text_secondary, null))
                            textSize = 16f
                            gravity = android.view.Gravity.CENTER
                            setPadding(32, 64, 32, 64)
                        }
                        binding!!.layoutConversations.addView(tvEmpty)
                    } else {
                        for (c in list) {
                            val cardLayout = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(32, 24, 32, 24)
                                val params = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 0, 0, 16)
                                }
                                layoutParams = params
                                setOnClickListener {
                                    val bundle = Bundle().apply {
                                        putString("conversationId", c.conversationId)
                                    }
                                    getNavController().navigate(R.id.action_conversations_to_chatScreen, bundle)
                                }
                            }
                            
                            val drawable = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 16f
                                setColor(resources.getColor(R.color.surface_slate, null))
                            }
                            cardLayout.background = drawable

                            val tvTitle = TextView(requireContext()).apply {
                                text = c.title
                                setTextColor(resources.getColor(R.color.text_primary, null))
                                textSize = 18f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            }

                            val tvSubtitle = TextView(requireContext()).apply {
                                val timeStr = dateFormat.format(Date(c.lastActiveTimestamp))
                                text = "Node ID: ${c.conversationId} | Active: $timeStr"
                                setTextColor(resources.getColor(R.color.text_secondary, null))
                                textSize = 14f
                                setPadding(0, 8, 0, 0)
                            }

                            cardLayout.addView(tvTitle)
                            cardLayout.addView(tvSubtitle)
                            binding!!.layoutConversations.addView(cardLayout)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
