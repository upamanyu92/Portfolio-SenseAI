package ai.portfoliosense

import java.util.Locale

internal enum class FlowKind(val title: String) {
    EARN("Earn"), SPEND("Spend"), REFUND("Refund"), EXCLUDED("Excluded")
}

internal data class BankAlert(val paise: Long, val kind: FlowKind, val reference: String?)

internal object AlertParser {
    private val money = Regex("""(?i)(?:₹|\bINR\.?|\bRs\.?)\s*[:.]?\s*([0-9]+(?:,[0-9]{2,3})*(?:\.[0-9]{1,2})?)(?![0-9,.]\d)""")
    private val action = Regex("""(?i)\b(debited|deducted|withdrawn|spent|paid|sent|credited|received|deposited|refunded|reversed)\b""")
    private val ignore = Regex("""(?i)\b(OTP|one.time.password|verification code|declined|failed|unsuccessful|scheduled|upcoming|mandate request|payment request|collect request|will be|to be debited|to be credited|could not be|was not|not debited|not credited|due date|is due|payment due|eligible for|offer valid|pre.approved)\b""")
    private val balance = Regex("""(?i)\b(?:avl\.?\s*(?:bal(?:ance)?)?|avail(?:able)?\.?\s*bal(?:ance)?|bal(?:ance)?(?:\s+is)?\s*[:=]|outstanding balance|available limit)\b""")
    private val bankContext = Regex("""(?i)\b(bank|a/c|acct|account|card|UPI|IMPS|NEFT|RTGS|ATM|wallet|VPA|POS)\b""")
    private val reference = Regex("""(?i)\b(?:UPI\s*(?:ref(?:erence)?(?:\s*(?:no|number|id))?|txn\s*id)|UTR|RRN|ref(?:erence)?\s*(?:no|number|id)?)\s*[.:#-]*\s*([A-Z0-9]{8,30})\b""")
    private val ownTransfer=Regex("""(?i)\b(?:self.transfer|own account transfer|transfer between your accounts|credit card (?:bill )?payment received)\b""")

    fun parse(sender:String, body:String):BankAlert? {
        val from=sender.trim()
        if(!from.matches(Regex("[A-Za-z][A-Za-z0-9-]{2,19}|[0-9]{3,6}"))) return null
        val line=body.replace(Regex("\\s+")," ").trim()
        if(ignore.containsMatchIn(line) || !bankContext.containsMatchIn(line)) return null
        val main=balance.find(line)?.let { line.take(it.range.first) } ?: line
        val amounts=money.findAll(main).toList()
        if(amounts.size!=1) return null
        val amount=amounts.single()
        val verbs=action.findAll(main).filter {
            // A trailing fraud helpline can say "never share details received by SMS".
            kotlin.math.abs(it.range.first-amount.range.first)<90
        }.toList()
        if(verbs.isEmpty()) return null
        val verb=verbs.minBy { minOf(kotlin.math.abs(it.range.last-amount.range.first),kotlin.math.abs(amount.range.last-it.range.first)) }.value.lowercase(Locale.ROOT)
        val kind=when {
            ownTransfer.containsMatchIn(main) -> FlowKind.EXCLUDED
            Regex("""(?i)\b(refund(?:ed)?|reversal|reversed)\b""").containsMatchIn(main) -> FlowKind.REFUND
            verb in setOf("credited","received","deposited") -> FlowKind.EARN
            else -> FlowKind.SPEND
        }
        val paise=runCatching { amount.groupValues[1].replace(",","").toBigDecimal().movePointRight(2).longValueExact() }.getOrNull() ?: return null
        if(paise<=0) return null
        return BankAlert(paise,kind,reference.find(line)?.groupValues?.get(1)?.uppercase(Locale.ROOT))
    }
}
