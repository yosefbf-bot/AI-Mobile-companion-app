package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionStatus
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed

@Composable
fun ConnectionStatusBadge(
    status: ConnectionStatus,
    hostIp: String,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ledPulse"
    )

    val ledColor: Color
    val statusText: String
    val isPulsing: Boolean

    when (status) {
        ConnectionStatus.DEMO -> {
            ledColor = StatusAmber
            statusText = "Demo — no desktop connection"
            isPulsing = false
        }
        ConnectionStatus.CONNECTED -> {
            ledColor = StatusGreen
            statusText = "Desktop Online"
            isPulsing = true
        }
        ConnectionStatus.DISCONNECTED -> {
            ledColor = StatusRed
            statusText = "Desktop Offline"
            isPulsing = false
        }
        ConnectionStatus.CONNECTING -> {
            ledColor = StatusAmber
            statusText = "Connecting..."
            isPulsing = true
        }
        ConnectionStatus.SYNCING -> {
            ledColor = StatusAmber
            statusText = "Syncing..."
            isPulsing = true
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .testTag("connection_status_badge")
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Red / Green Light Indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(14.dp)
        ) {
            if (isPulsing) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(ledColor.copy(alpha = 0.35f))
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ledColor)
                    .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    .testTag("connection_led_indicator")
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (status == ConnectionStatus.CONNECTED) ledColor else MaterialTheme.colorScheme.onSurface
            )
        )
    }
}
