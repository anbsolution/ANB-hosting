package com.anbhosting.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LocalHeroHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    backLabel: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(listOf(LocalNavy, Color(0xFF1C3472), Color(0xFF263F85))),
                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            )
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("‹ $backLabel", color = Color.White, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Text(eyebrow.uppercase(), color = Color(0xFF9EB8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3f).sp)
        Text(subtitle, color = Color(0xFFD2DCF4), fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
internal fun LocalPremiumCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, Color(0xFFE7EBF4)),
        content = content,
    )
}

@Composable
internal fun LocalStatusPill(
    text: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = background, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun LocalMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    LocalPremiumCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp)) {
            Text(label, color = LocalMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(value, color = LocalNavy, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
