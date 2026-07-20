package com.qualcomm.meshmind.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qualcomm.meshmind.R
import com.qualcomm.meshmind.databinding.FragmentChatScreenBinding
import com.qualcomm.meshmind.viewmodel.ChatViewModel
import com.qualcomm.meshmind.viewmodel.ChatViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen secure mesh chat session screen.
 * Driven by [ChatViewModel], reads messages from Room and dispatches via MMP.
 */
class ChatScreenFragment : BaseFragment() {

    private var binding: FragmentChatScreenBinding? = null
    private lateinit var viewModel: ChatViewModel
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentChatScreenBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val conversationId = arguments?.getString("conversationId") ?: "neighbor_node_1"
        viewModel = ViewModelProvider(this, ChatViewModelFactory(conversationId))[ChatViewModel::class.java]

        binding!!.toolbar.title = "Secure Chat: $conversationId"
        binding!!.toolbar.setNavigationOnClickListener { getNavController().popBackStack() }

        // Observe messages and rebuild the chat bubbles list
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { messages ->
                    binding!!.layoutMessages.removeAllViews()
                    if (messages.isEmpty()) {
                        val tvEmpty = TextView(requireContext()).apply {
                            text = "No messages yet. Send a secure mesh message below."
                            setTextColor(resources.getColor(R.color.text_secondary, null))
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setPadding(32, 48, 32, 48)
                        }
                        binding!!.layoutMessages.addView(tvEmpty)
                    } else {
                        val identityMgr = com.qualcomm.meshmind.core.dependency.ServiceLocator.get(com.qualcomm.meshmind.identity.DeviceIdentityManager::class.java)
                        val localNodeId = identityMgr.resolveNodeId().lowercase(java.util.Locale.ROOT).trim()
                        
                        for (msg in messages) {
                            val isOutgoing = msg.entity.senderNodeId == localNodeId
                            addMessageBubble(msg.entity.body, msg.entity.senderNodeId, msg.entity.timestamp, isOutgoing, msg.entity.deliveryStatus)
                        }
                        // Auto-scroll to bottom
                        binding!!.scrollView.post {
                            binding!!.scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                }
            }
        }

        // Send button action
        binding!!.btnSend.setOnClickListener {
            val text = binding!!.etMessage.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                val traceId = java.util.UUID.randomUUID().toString()
                com.qualcomm.meshmind.diagnostics.ChatDeliveryDiagnostics.logEvent(
                    traceId = traceId,
                    stage = "CHAT_SEND_CLICKED"
                )
                
                viewLifecycleOwner.lifecycleScope.launch {
                    binding!!.btnSend.isEnabled = false
                    val success = viewModel.sendMessage(text, false, traceId)
                    if (success) {
                        binding!!.etMessage.text?.clear()
                    }
                    binding!!.btnSend.isEnabled = true
                }
            }
        }

        // Periodic refresh to pick up incoming messages
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(3000)
                viewModel.loadMessages()
            }
        }
    }

    private fun addMessageBubble(content: String, senderNodeId: String, timestamp: Long, isOutgoing: Boolean, status: String) {
        val context = requireContext()
        val bubbleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isOutgoing) Gravity.END else Gravity.START
                setMargins(if (isOutgoing) 80 else 0, 0, if (isOutgoing) 0 else 80, 12)
                topMargin = 8
            }
            layoutParams = params
            setPadding(20, 14, 20, 14)

            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(
                    if (isOutgoing)
                        resources.getColor(R.color.colorPrimary, null)
                    else
                        resources.getColor(R.color.surface_slate, null)
                )
            }
            background = drawable
        }

        val tvContent = TextView(context).apply {
            text = content
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 15f
        }

        val tvMeta = TextView(context).apply {
            val timeStr = dateFormat.format(Date(timestamp))
            val statusStr = if (isOutgoing) " [$status]" else ""
            text = "${if (isOutgoing) "You" else senderNodeId}  •  $timeStr$statusStr"
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 11f
            setTypeface(null, Typeface.ITALIC)
            setPadding(0, 4, 0, 0)
        }

        bubbleLayout.addView(tvContent)
        bubbleLayout.addView(tvMeta)

        // Wrap in outer container to handle gravity correctly
        val outerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        outerLayout.addView(bubbleLayout)
        binding!!.layoutMessages.addView(outerLayout)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
