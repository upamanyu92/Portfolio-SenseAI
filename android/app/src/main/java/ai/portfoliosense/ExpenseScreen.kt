package ai.portfoliosense

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ExpenseScreen() {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val scope=rememberCoroutineScope()
    val read=Manifest.permission.READ_SMS
    val receive=Manifest.permission.RECEIVE_SMS
    var enabled by remember { mutableStateOf(SmsLedger.enabled(context)) }
    var canRead by remember { mutableStateOf(SmsLedger.permitted(context,read)) }
    var canReceive by remember { mutableStateOf(SmsLedger.permitted(context,receive)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked=true
        canRead=SmsLedger.permitted(context,read)
        canReceive=SmsLedger.permitted(context,receive)
        enabled=canRead
        SmsLedger.setEnabled(context,enabled)
    }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) {
                canRead=SmsLedger.permitted(context,read)
                canReceive=SmsLedger.permitted(context,receive)
                if(enabled && canRead) SmsLedger.enqueue(context,false)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month=YearMonth.parse(monthText)
    var limit by rememberSaveable(monthText) { mutableIntStateOf(25) }
    val revision by SmsLedger.changes.collectAsState()
    var ledger by remember(monthText) { mutableStateOf(MonthLedger(emptyMap(),emptyList(),0)) }
    LaunchedEffect(revision,month,limit) { ledger=withContext(Dispatchers.IO){ SmsLedger.month(context,month,limit) } }
    Panel("Bank SMS tracking") {
        Text("Recognized INR transaction alerts update your ledger. Only the sender, amount, date, classification and duplicate-check identifier are saved on this device.")
        if(enabled && canRead) {
            Text(if(canReceive) "Automatic SMS tracking is on." else "Inbox import is on. Allow incoming SMS access for prompt updates.")
            Row(horizontalArrangement=spacing) {
                OutlinedButton(onClick={ SmsLedger.enqueue(context,false) }){ Text("Sync inbox") }
                TextButton(onClick={SmsLedger.setEnabled(context,false);enabled=false}) {Text("Pause")}
            }
        } else {
            Button(onClick={ asked=true;permissions.launch(arrayOf(read,receive)) }) {Text(if(asked)"Allow SMS access" else "Enable SMS tracking")}
            if(asked && !canRead) Text("Android must grant SMS access before transactions can be imported.")
        }
        if((asked || enabled) && (!canRead || !canReceive)) TextButton(onClick={
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName)))
        }) {Text("Open app settings")}
    }
    Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={monthText=month.minusMonths(1).toString()}) {Text("Previous")}
        Text(month.format(DateTimeFormatter.ofPattern("MMM yyyy")),style=MaterialTheme.typography.titleMedium)
        TextButton(onClick={monthText=month.plusMonths(1).toString()},enabled=month<YearMonth.now()){Text("Next")}
    }
    val earn=ledger.totals[FlowKind.EARN] ?: 0L
    val spend=ledger.totals[FlowKind.SPEND] ?: 0L
    val refunds=ledger.totals[FlowKind.REFUND] ?: 0L
    Panel("Monthly cash flow") {
        Metric("Earn · incoming credits",money(earn/100.0))
        Metric("Spend · outgoing debits",money(spend/100.0))
        if(refunds>0) Metric("Refunds",money(refunds/100.0))
        HorizontalDivider()
        Metric("Net cash flow",money((earn+refunds-spend)/100.0))
    }
    Panel("Transactions") {
        if(ledger.count==0) Text("No recognized bank transactions for this month.")
        else Text("Change a classification to correct an alert or exclude an internal transfer.")
        ledger.entries.forEach { entry ->
            key(entry.id) {
                var menu by remember { mutableStateOf(false) }
                HorizontalDivider()
                Metric(entry.sender,money(entry.paise/100.0))
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(entry.at)),color=palette.onSurfaceVariant)
                Box {
                    TextButton(onClick={menu=true}) { Text(entry.kind.title+" ▾") }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                        FlowKind.entries.forEach { kind -> DropdownMenuItem(text={Text(kind.title)},onClick={
                            menu=false
                            scope.launch(Dispatchers.IO){SmsLedger.classify(context,entry.id,kind)}
                        }) }
                    }
                }
            }
        }
        if(ledger.count>limit) TextButton(onClick={limit+=25}){Text("Show more transactions")}
    }
}
