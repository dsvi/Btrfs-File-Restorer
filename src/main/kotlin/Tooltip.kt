import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.DpOffset

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip( text: String, wrapOver: @Composable () -> Unit){
    TooltipArea(
        tooltip = {
            // composable tooltip content
            Surface(
                modifier = Modifier.shadow(4.dp),
                //color = Color(255, 255, 210),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(10.dp)
                )
            }
        },
        delayMillis = 600, // in milliseconds
        tooltipPlacement = TooltipPlacement.CursorPoint(
            offset = DpOffset(20.dp, 20.dp),
            alignment = Alignment.BottomEnd,
        )
    ) {
        wrapOver()
    }
}